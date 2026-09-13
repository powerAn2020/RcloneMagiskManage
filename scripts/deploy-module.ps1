param(
    [string]$DeviceId = "emulator-5554",
    [switch]$RestartService
)

$adb = if (Get-Command adb -ErrorAction SilentlyContinue) {
    "adb"
} elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    "$env:ANDROID_HOME\platform-tools\adb.exe"
} else {
    "adb"
}

Write-Host ">>> Deploying Rclone Root Manager module to $DeviceId..."

$modDir = "dist\rclone-manager-x86_64-linux-android"
if (-not (Test-Path $modDir)) {
    Write-Error "Module dist directory $modDir does not exist. Run scripts/build-module.sh first."
    exit 1
}

# 1. Ensure remote directory exists and clear any legacy system/ overlay directory
& $adb -s $DeviceId shell "mkdir -p /data/adb/modules/rclone-manager/bin /data/adb/rclone-manage/runtime /data/adb/rclone-manage/logs /data/adb/rclone-manage/db && rm -rf /data/adb/modules/rclone-manager/system"

# 2. Push module files
& $adb -s $DeviceId push "$modDir\." /data/adb/modules/rclone-manager/

# 3. Ensure permissions
& $adb -s $DeviceId shell "chmod 755 /data/adb/modules/rclone-manager/bin/* /data/adb/modules/rclone-manager/*.sh && chmod 644 /data/adb/modules/rclone-manager/module.prop"

# 4. Optional restart
if ($RestartService) {
    Write-Host ">>> Restarting Gateway daemon..."
    & $adb -s $DeviceId shell "nohup sh /data/adb/modules/rclone-manager/service.sh </dev/null >/dev/null 2>&1 &"
    Start-Sleep -Seconds 2
}

# 5. Fix directory and socket permissions for emulator / testing access
& $adb -s $DeviceId shell "chmod 755 /data/adb /data/adb/modules && chmod 777 /data/adb/rclone-manage /data/adb/rclone-manage/runtime 2>/dev/null || true"
& $adb -s $DeviceId shell "chmod 666 /data/adb/rclone-manage/runtime/gateway.sock 2>/dev/null || true"

Write-Host ">>> Deployment complete!"
