# 单应用私有目录隔离挂载（Non-Broadcast App Mount）技术方案

## 1. 需求与背景
在 Android Magisk / KernelSU 环境下，现有的云存储挂载通常通过系统全局命名空间（Global Namespace）广播，所有具有存储读取权限的文件管理器及 App 均能扫描并访问挂载点。
本方案旨在实现**指定路径/云盘仅挂载到特定目标 App（如播放器、专用相册或内部办公应用）的私有目录下**，实现：
1. **防系统广播与隐私隔离**：全局文件管理器、媒体扫描器（MediaStore）及其他第三方 App 均不可见或不可读取该挂载目录。
2. **免全局挂载空间依赖**：控制端 App（Rclone Root Manager）无需全局挂载空间即可下发配置。
3. **权限与 SELinux 无缝适配**：挂载点自动匹配目标 App 的 UID/GID 及安全上下文，保证目标 App 正常读写。

---

## 2. Linux 与 Android 核心机制原理

### 2.1 Mount Namespace 单向隔离机制
- **Zygote 架构特性**：Android 系统的 Zygote 在 fork 每个应用进程时，会调用 `unshare(CLONE_NEWNS)` 为该应用创建独立的 Mount Namespace，并通常将其与主系统树标记为 `MS_SLAVE`。
- **单向扩散定理**：
  - 从 `MS_SHARED`（全局 init 空间）挂载的内容会向下同步给所有 `slave` 空间。
  - 但**在 `slave` 命名空间内部执行的挂载，绝对不会反向传播到全局父空间或其它并列的 App 空间中**。
- **精准穿透**：Root 守护进程通过 `nsenter -t <target_pid> -m mount ...`，可在运行时切入目标 App 的虚拟文件系统树，将挂载点直接挂入其私有视野中。

---

## 3. 技术架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                 Rclone Root Manager (前端 App)              │
│       提供挂载配置界面：选择目标应用包名、挂载子目录、隔离模式        │
└──────────────────────────────┬──────────────────────────────┘
                               │ IPC (Unix Domain Socket)
┌──────────────────────────────▼──────────────────────────────┐
│                  rclone-gateway (守护进程)                  │
│  1. 解析目标包名 UID / GID (通过 pm dump 或 stat /data/data)  │
│  2. 验证路径合法性，安全放行 /data/data/<pkg> 及 Android/data │
│  3. 抑制全局衍生绑定（跳过 /data/media/0/* 的 bind-mount）     │
│  4. 启动 rclone mount 并注入特定 UID/GID/allow-other 参数    │
│  5. (可选强隔离) 通过 nsenter 注入目标进程 Mount Namespace    │
└──────────────────────────────┬──────────────────────────────┘
                               │ FUSE3 Mount
┌──────────────────────────────▼──────────────────────────────┐
│                    目标应用私有空间 (Target App)              │
│   路径: /data/user/0/<package>/files/cloud                  │
│   - 仅当前 App 可读写                                        │
│   - 系统“文件”/其他 App 无法穿透 (0700 DAC 阻断 + 非广播)       │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心实施方案

### 方案 1：应用沙盒原生权限强隔离（开机即稳，推荐默认）
- **挂载目标**：目标 App 私有目录（如 `/data/user/0/<package>/files/<name>`）。
- **挂载参数适配**：
  - 查询目标 App 的 Linux UID/GID（如 `10195`）。
  - 执行 `rclone mount` 时附加：
    ```bash
    --uid <target_uid> --gid <target_gid> --allow-other --dir-perms 0700 --file-perms 0600
    ```
- **广播抑制**：
  - 在 Gateway 的 `derived_bind_target` 中识别目标路径，当路径属于应用私有路径时，**跳过向公共 `/data/media/0/*` 的自动 bind 扩散**。
- **安全性**：
  - Linux 内核严格执行 DAC 权限控制，`/data/data/<package>` 属主为目标 App，其他任何非 root 应用连 `cd` / `ls` 该目录的权限都没有（`Permission Denied`）。
  - 媒体扫描器（MediaStore）默认忽略 `/data/data`，云盘文件不会被缩略图数据库抓取。

### 方案 2：进程命名空间注入（Runtime NSENTER 强隐蔽）
- **挂载目标**：动态切入目标 App 正在运行的进程。
- **执行流程**：
  1. 通过 `pidof <package>` 获取活跃 PID。
  2. 守护进程在受保护的中转目录 `/data/adb/rclone-manage/runtime/mounts/<id>` 启动底层 Rclone FUSE。
  3. 执行单向绑定：
     ```bash
     nsenter -t $PID -m mount --bind /data/adb/rclone-manage/runtime/mounts/<id> /data/data/<package>/files/<name>
     ```
  4. 此时不仅文件管理器完全看不到中转层，甚至在全局 `mount` 列表里也看不到该绑定。

---

## 5. 详细工程改动规划

### 5.1 后端 Gateway (Rust)
1. **数据模型扩展 (`MountProfile`)**：
   - 增加 `isolated_package: Option<String>`（目标应用包名）。
   - 增加 `isolated_mode: Option<String>`（`"sandbox"` 或 `"namespace"`）。
2. **安全路径校验 (`valid_mount`)**：
   - 放行 `/data/data/<package>/*` 和 `/data/user/0/<package>/*` 前缀（前提是符合安全包名与子路径规范）。
3. **UID/GID 动态获取**：
   - 在 Linux/Android 平台通过目标应用的数据目录 `stat` 提取对应的 UID/GID。
4. **命令行构建 (`rclone mount`)**：
   - 若指定了隔离包名，自动追加 `--uid <UID> --gid <GID> --allow-other`。
   - 禁用全局衍生绑定 `derived_bind_target`。

### 5.2 前端 Companion App (Kotlin / Jetpack Compose)
1. **挂载创建/编辑弹窗 (`MountsScreen.kt`)**：
   - 增加“挂载目标类型”选择器：
     - **系统通用挂载**（默认，`/mnt/rclone-*` 或 `/storage/emulated/0/*`，全系统可见）
     - **应用专属隔离挂载**（可输入或选择已安装的目标应用包名，如 `com.example.app`，自动补全私有挂载路径 `/data/data/<pkg>/files/<mount_name>`）
2. **应用选择与自动感知**：
   - 读取系统已安装应用列表，提供应用选择下拉框，提升易用性。

---

## 6. 验证计划
1. **功能验证**：配置挂载到某一目标应用（如已安装的测试 App），验证目标 App 可直接打开并读写云盘内文档/视频。
2. **隔离性验证**：
   - 打开系统自带“文件”管理器或 MT 管理器，确认全局存储及 `/mnt` 下无任何该挂载点信息。
   - 以另一普通非 root 应用身份尝试读取该目录，确认严格返回 `Permission Denied`。
3. **生命周期验证**：测试目标 App 重启、手机开机自启等场景，确保挂载状态一致。
