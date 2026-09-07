#!/system/bin/sh
# Magisk / KernelSU / APatch Module Installer
# Android Rclone Root Manager - All-in-One Module (Pure Service, No System Mount)

MODPATH=${MODPATH:-${0%/*}}

ui_print "****************************************"
ui_print "*   Android Rclone Root Manager V1.2   *"
ui_print "*  All-in-One Module (No System Mount) *"
ui_print "****************************************"

# 1. 架构检测与多架构二进制布局选择 (若打包了多架构目录)
case "$ARCH" in
  arm64)
    ABI="arm64-v8a"
    ;;
  arm)
    ABI="armeabi-v7a"
    ;;
  x64|x86_64)
    ABI="x86_64"
    ;;
  x86)
    ABI="x86"
    ;;
  *)
    ui_print "! 未知架构: $ARCH，使用默认二进制"
    ABI=""
    ;;
esac

ui_print "- 设备架构: $ARCH ($ABI)"

# 如果模块包中包含特定架构子目录，将其拷贝到 $MODPATH/bin
if [ -n "$ABI" ] && [ -d "$MODPATH/prebuilt/$ABI" ]; then
  ui_print "- 正在安装 $ABI 架构核心组件..."
  mkdir -p "$MODPATH/bin"
  cp -af "$MODPATH/prebuilt/$ABI/"* "$MODPATH/bin/"
elif [ -n "$ABI" ] && [ -d "$MODPATH/bin/$ABI" ]; then
  ui_print "- 正在应用 $ABI 架构可执行文件..."
  mv "$MODPATH/bin/$ABI/"* "$MODPATH/bin/"
  rm -rf "$MODPATH/bin/$ABI"
fi

# 清理预编译架构目录，避免占用空间
rm -rf "$MODPATH/prebuilt"

# 校验必要文件
if [ ! -f "$MODPATH/bin/rclone-gateway" ]; then
  abort "! 错误: 缺少 rclone-gateway 守护程序"
fi
if [ ! -f "$MODPATH/bin/rclone" ]; then
  ui_print "⚠️ 警告: 模块未携带内置 rclone 二进制，请确保系统已安装 rclone"
else
  ui_print "- 已安装内置 FUSE3 rclone 二进制"
fi
if [ ! -f "$MODPATH/bin/fusermount3" ]; then
  ui_print "⚠️ 提示: 未检测到 fusermount3，挂载将尝试使用系统环境中的组件"
else
  ui_print "- 已安装内置 fusermount3 挂载组件"
fi

# 2. 准备持久化运行时目录
ui_print "- 初始化数据持久化目录 /data/adb/rclone-manage..."
ROOT=/data/adb/rclone-manage
mkdir -p "$ROOT/db" "$ROOT/keys" "$ROOT/secrets" "$ROOT/runtime" "$ROOT/logs" "$ROOT/backups" "$ROOT/migrations" "$ROOT/cache"
chmod 700 "$ROOT" 2>/dev/null || true
chmod 700 "$ROOT"/* 2>/dev/null || true

# 3. 设置权限 (严格保障安全性)
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
chmod 0755 "$MODPATH"/*.sh 2>/dev/null || true

# 确保绝不创建 system 目录 (避免任何系统镜像挂载)
rm -rf "$MODPATH/system"

ui_print "✅ 安装完成！模块为纯服务模式运行，无需修改系统分区。"
