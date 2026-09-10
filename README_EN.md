# Android Rclone Root Manager

<p align="center">
  <b>Modern Root-Level Cloud Storage Management & Privileged Control Plane for Android</b><br>
  <span>Full-featured production-ready refactoring based on <code>NewFuture/rclone-fuse3-magisk</code></span>
</p>

<p align="center">
  <b>English</b> | <a href="README.md"><b>简体中文</b></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Release-v1.1.0-blue.svg" alt="Release v1.1.0">
  <img src="https://img.shields.io/badge/Android-API%2029--35-green.svg" alt="Android API 29-35">
  <img src="https://img.shields.io/badge/Root-Magisk%20%7C%20KernelSU%20%7C%20APatch-orange.svg" alt="Root">
  <img src="https://img.shields.io/badge/Gateway-Rust%202024%20(Axum%20%2F%20Tokio)-red.svg" alt="Rust Gateway">
  <img src="https://img.shields.io/badge/App-Kotlin%20%7C%20Compose%20M3-purple.svg" alt="Compose M3">
  <img src="https://img.shields.io/badge/License-GPL--3.0-lightgrey.svg" alt="License">
</p>

---

## 📖 1. Project Overview

**Android Rclone Root Manager** is a privileged cloud storage control platform engineered specifically for rooted Android environments. It deeply integrates the robust cloud synchronization and mounting engine of `rclone` with the modern Android mobile experience, systematically resolving historical pain points such as uncontrolled root privileges, lack of background management, missing security isolation, and fragile shell scripts.

The project employs an **All-in-One Pure Service Module (No System Mount)** architecture. Users only need to install a single module: **no intrusion, no overlay mounts, and zero modifications to the Android System partition**, delivering comprehensive FUSE3 mounting, background watchdogs, automated task scheduling, and encrypted protection out of the box.

### Architectural Principles & Layer Separation

```text
┌─────────────────────────────────────────────────────────────┐
│                 Android App (Control Plane)                 │
│  Kotlin · Jetpack Compose · Material 3 · Monitoring · Jobs  │
└──────────────────────────────┬──────────────────────────────┘
                               │ libsu / Unix Domain Socket (Mode 0600)
                               ▼ (Strict Typed Contract · No Raw RC · No Shell)
┌─────────────────────────────────────────────────────────────┐
│               Root Gateway (Security Boundary)              │
│    Rust Daemon · SQLite WAL · XChaCha20 Secrets · Scoped ACL│
│    HMAC Replay Guard · Safe Mode · Mutex Slot Claims · Audit│
└──────────────────────────────┬──────────────────────────────┘
                               │ Internal Managed Subprocesses / Dedicated Root /data/adb/rclone-manage
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 rclone Core (Data Engine)                   │
│   FUSE3 Kernel Mount · Derived Bind Target · Crypt · Cloud  │
└──────────────────────────────┘
```

1. **Complete Decoupling of UI & Privilege**: The Android App acts purely as a non-root controller. Terminating or background-killing the app will not disrupt active background syncs or live mounts.
2. **Zero Raw RC & Shell Exposure**: Direct access to raw rclone RC ports or arbitrary shell execution channels is strictly barred. All operations must pass through Gateway parameter allowlists and typed REST contracts.
3. **Strict Directory Isolation**: State, SQLite databases, and encrypted secrets persist strictly in `/data/adb/rclone-manage/` (`0700`). The module directory `/data/adb/modules/rclone-manager/` hosts only read-only binaries and lifecycle scripts.
4. **End-to-End Cryptographic Protection**: Remote credentials and Crypt secrets are encrypted using XChaCha20-Poly1305. API endpoints expose only typed references (`secretRef`), never plaintext secrets.

---

## 📱 2. Screenshots Gallery

| Remote Storage Management | Cloud File Explorer | Job Orchestration & Queues |
|:---:|:---:|:---:|
| ![Remotes](docs/screenshots/remotes.png) | ![Files](docs/screenshots/files.png) | ![Jobs](docs/screenshots/jobs.png) |

| Add Remote Dialog | Legacy Config Migration | Settings & Diagnostics |
|:---:|:---:|:---:|
| ![Add Remote](docs/screenshots/add_remote.png) | ![Migration](docs/screenshots/migration.png) | ![Settings](docs/screenshots/settings.png) |

| Material 3 Dark Theme |
|:---:|
| ![Dark Theme](docs/screenshots/dark_mode.png) |

---

## 🎯 3. Practical Use Cases

### Scenario 1: Seamless Cloud Storage Mounting (FUSE Virtual Disks)
- **Problem**: Phone storage is limited, yet downloading huge video libraries or lossless FLAC files from OneDrive, Google Drive, or WebDAV is slow and occupies internal storage.
- **Solution**: Mount cloud remotes to `/mnt/rclone-<name>`. The Gateway automatically derives the restricted shared-storage target `/data/media/0/<name>`. Gallery apps, file managers, VLC, and MX Player can immediately stream high-bitrate media without prior downloading.

### Scenario 2: Automated Background Backups with Policy Gates
- **Problem**: Traditional sync scripts trigger blindly regardless of mobile data or low battery conditions, leading to unexpected data charges or battery drain.
- **Solution**: Schedule background Jobs with Policy Gates: configure tasks to execute strictly when connected to **unmetered Wi-Fi** and **charging**, supporting incremental Sync, one-way Copy, and two-way Bisync.

### Scenario 3: Zero-Knowledge Transparent Cloud Encryption (Crypt)
- **Problem**: Storing sensitive documents and private photos on public clouds poses data inspection and leak hazards.
- **Solution**: Overlay an rclone Crypt profile on top of any remote. Passwords are typed and obscured via AES-CTR, keeping both file names and directory trees encrypted before touching the wire.

---

## 🚀 4. Mounting Modes & Architecture

Mounting is one of the core subsystems of this project. In the Android App's Mount Profile creation/editing dialog, the system provides two core mounting modes, alongside granular VFS cache tuning:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Root Gateway Mount Orchestrator                    │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
        ┌──────────────────────────────┴──────────────────────────────┐
        ▼                                                             ▼
[Mode 1: Global Mount]                                        [Mode 2: Isolated App Mount]
  Mount point: /mnt/rclone-<name>                               Mount point: /data/data/<pkg>/files/rclone/<name>
  Auto-derived bind to /data/media/0/<name>                     Bound to target package name, dynamic UID/GID matching
  (Galleries, VLC player, stock file managers all accessible)   (Physical DAC 0700 isolation, suppresses broadcast/scan)
```

### 1. Global Mount Mode
- **Mechanism**: The underlying engine creates a FUSE3 mount at `/mnt/rclone-<name>`. Once FUSE readiness is confirmed, the Gateway automatically derives and safely binds this to `/data/media/0/<name>` (the user-accessible `/sdcard/<name>`).
- **Use Cases**: Public media libraries and general storage expansion. Stock file managers, VLC, MX Player, and Kodi work seamlessly out of the box with zero setup.
- **Safety Guarantee**: Custom arbitrary bind targets are rejected. Unmounting automatically unbinds derived targets and cleans up worker configs, preventing orphaned zombie mounts.

### 2. Isolated App Mount Mode
- **Mechanism**: Select "应用专属" (Isolated App Mount) and choose the target application (via the built-in installed app picker). The mount path automatically targets that application's private sandbox (e.g., `/data/data/<package>/files/rclone/<name>`). The Gateway dynamically resolves the package's Linux UID/GID and injects `--uid <UID> --gid <GID> --dir-perms 0700 --file-perms 0600` into rclone.
- **Key Advantages**:
  - **Broadcast Suppression**: Skips the automatic bind propagation to `/data/media/0/*`, ensuring non-broadcast privacy.
  - **Physical DAC Isolation**: Enforces Linux kernel DAC permissions (`0700`), strictly returning `Permission Denied` to any other non-root app or file manager attempting entry.
  - **MediaStore Exemption**: Android's media scanner ignores private app directories by default; private photos and documents will never leak into system galleries.
- **Use Cases**: Private photo vaults, dedicated offline streaming apps, and confidential enterprise workspaces.

> 💡 **Advanced Architecture Note**: The `nsenter`-based Mount Namespace injection investigated in [`docs/isolated-app-mount-design.md`](docs/isolated-app-mount-design.md) represents an exploratory research approach. The current production Android App implements the more reliable, boot-stable **App Sandbox Native DAC Isolation** strategy.

### 3. VFS Cache Strategies & Safeguards
- **VFS Cache Modes**:
  - `off` / `minimal`: Instant direct streaming with zero local flash consumption.
  - `writes`: Buffers write operations locally to ensure upload integrity.
  - `full` (Recommended for high-bitrate media): Multithreaded chunked read-ahead with seek buffering for seamless video playback.
- **Automated Eviction**: Configure `cacheMaxSize` (e.g., 10G) and `cacheMaxAge` (e.g., 24h); the supervisor purges aged cache chunks via LRU to prevent running out of flash storage.
- **Read-Only Toggle**: Enables protocol-level write protection to prevent accidental edits or deletions of valuable cloud data.

---

## 🛡️ 5. KernelSU (KSU) Least-Privilege Profile Specification

Under modern Android security paradigms, **"Root access must never imply indiscriminate full-system privileges"**. This project provides an optimized App Profile tailored for [KernelSU](https://kernelsu.org/) (located in [`docs/app_profile.json`](docs/app_profile.json)), strictly upholding the **Principle of Least Privilege**:

```json
{
    "version": 1,
    "package": "io.github.poweran2020.rclone.manager",
    "name": "Rclone Root Manager",
    "root": {
        "enabled": true,
        "uid": 0,
        "gid": 0,
        "groups": [ 0, 2000, 1015, 1028, 3003 ],
        "capabilities": [
            "CAP_DAC_OVERRIDE",
            "CAP_DAC_READ_SEARCH",
            "CAP_FOWNER",
            "CAP_KILL",
            "CAP_NET_ADMIN"
        ],
        "namespace": "inherited",
        "selinux": "u:r:ksu:s0"
    }
}
```

### Deep Dive: Minimal Capability Breakdown

| Configuration Item | Value | Least-Privilege Security Rationale |
|:---|:---|:---|
| **Identity** | `uid: 0`, `gid: 0` | Grants ownership access required to interface with Unix Domain Socket (`0600`) and FUSE nodes. |
| **Group 2000** | `shell` | Allows necessary local shell IPC probing and debugging telemetry. |
| **Group 1015 & 1028** | `sdcard_rw`, `sdcard_r` | Grants read/write permissions to shared storage `/sdcard` & `/data/media/0` for bind mount visibility. |
| **Group 3003** | `inet` | Grants socket creation capabilities for cloud sync and LAN pairing authentication. |
| **CAP_DAC_OVERRIDE** | Linux Capability | Bypasses standard file DAC checks to read/write the dedicated `/data/adb/rclone-manage` directory. |
| **CAP_DAC_READ_SEARCH** | Linux Capability | Allows traversing system directories for file pickers and path canonicalization. |
| **CAP_FOWNER** | Linux Capability | Enables managing and reclaiming temporary config files and unlinked mount descriptors. |
| **CAP_KILL** | Linux Capability | Grants ability to signal and terminate specific worker PIDs, **preventing catastrophic global `pkill`**. |
| **CAP_NET_ADMIN** | Linux Capability | Manages network binding interfaces and LAN TLS transport policies. |
| **Namespace** | `inherited` | **Critical invariant**: Inherits root mount namespace, ensuring FUSE mount points propagate system-wide. |
| **SELinux** | `u:r:ksu:s0` | Runs within KernelSU's dedicated privileged domain with granular policy boundaries. |

> **Security Advantage**: Through this profile, the App is granted strictly 5 necessary Linux capabilities. All other dangerous capabilities (e.g., raw hardware I/O, system clock modifications, kernel module loading) are stripped by the kernel.

---

## ⚙️ 6. Technical Invariants & Security Architecture

### Core Engineering Invariants
1. **Fixed Command Contract**: The Android controller invokes only the restricted Gateway CLI:
   ```bash
   /data/adb/modules/rclone-manager/bin/rclone-gateway request \
     --socket /data/adb/rclone-manage/runtime/gateway.sock \
     --method GET --path /api/v1/system/health
   ```
2. **HMAC Anti-Replay & Atomic Nonce Consumption**: Single-use nonces are persisted and atomically claimed via SQLite `IMMEDIATE` transactions; request timestamps enforce a strict $\pm 60$s window.
3. **Two-Step Destructive Operation Confirmation**: Deleting remotes or batch file purges requires a dry-run preview followed by consuming a 60-second atomic Confirmation Token.
4. **ClientSource Rate Limiting**: Strongly-typed rate limiting isolates pairing abuse with 60s cooldowns and HTTP 429 backpressure.
5. **Redacted Auditing**: Audit entries persist executor UID, duration, and SHA-256 path hashes—never plaintext file paths. Diagnostics automatically mask credential lines with `[REDACTED]`.
6. **Watchdog Crash Counter & Safe Mode**: The service supervisor monitors Gateway status. Three consecutive crashes lock the system into **Safe Mode**, disabling automatic mounts and scheduled jobs to preserve device stability.

---

## 🛠️ 7. Build & Installation Guide

The project utilizes standard Gradle and Cargo multi-target cross-compilation toolchains, free from hardcoded machine paths.

### Prerequisites
- **Java**: JDK 17 or higher
- **Android SDK**: API Level 35 (Compile SDK 37, Min SDK 29), Android NDK r26+
- **Rust Toolchain**: Rust 1.85+ (Edition 2024) with Android targets:
  ```bash
  rustup target add x86_64-linux-android aarch64-linux-android
  ```

### 1. Build Gateway Privileged Daemon (Rust)
Set your NDK Clang environment variables and compile:
```bash
# Example for x86_64 target (or aarch64-linux-android for arm64)
cargo build --release --target x86_64-linux-android -p rclone-gateway
```

### 2. Build Android Controller (Kotlin / Compose)
Use the standard Gradle wrapper:
```bash
# Assemble debug APK
./gradlew assembleDebug

# Install directly to attached target
./gradlew installDebug
```

### 3. Package Magisk / KernelSU Module
Execute the package script to produce the All-in-One module ZIP:
```bash
# Linux / macOS / WSL
sh scripts/build-module.sh

# Windows PowerShell
powershell -File scripts/package-module.ps1
```
The flashable zip output is written to `dist/`, ready to flash in Magisk, KernelSU, or APatch Manager.

---

## 📄 8. License & Acknowledgements

- **License**: Released under the [GPL-3.0-or-later](LICENSE).
- **Acknowledgements**:
  - [NewFuture/rclone-fuse3-magisk](https://github.com/NewFuture/rclone-fuse3-magisk): Pioneered Android FUSE3 cross-compilation and module packaging foundations.
  - [rclone/rclone](https://github.com/rclone/rclone): The Swiss Army knife for cloud storage.
  - [topjohnwu/libsu](https://github.com/topjohnwu/libsu): Robust Android Root IPC bridge library.
