# PowerShell Packaging Script for RcloneMagiskManage
# Builds gateway, integrates APK, and outputs flashable Magisk zip for x86_64 & arm64
param(
    [ValidateSet("all", "x86_64", "arm64", "aarch64-linux-android", "x86_64-linux-android")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
$Root = (Get-Item $PSScriptRoot).Parent.FullName

# 1. Ensure NDK & Rust Toolchain Environment
$NdkRoot = if ($env:NDK_ROOT) { $env:NDK_ROOT } else { "C:/Development/JetBrains/AndroidSDK/ndk/26.3.11579264" }
$LlvmBin = "$NdkRoot/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (Test-Path "$LlvmBin/clang.exe") {
    $env:PATH = "$LlvmBin;$env:PATH"
    $env:CC_x86_64_linux_android = "$LlvmBin/clang.exe"
    $env:CXX_x86_64_linux_android = "$LlvmBin/clang++.exe"
    $env:AR_x86_64_linux_android = "$LlvmBin/llvm-ar.exe"
    $env:CC_aarch64_linux_android = "$LlvmBin/aarch64-linux-android34-clang.cmd"
    $env:CXX_aarch64_linux_android = "$LlvmBin/aarch64-linux-android34-clang++.cmd"
    $env:AR_aarch64_linux_android = "$LlvmBin/llvm-ar.exe"
}

# 2. Verify APK exists or build it
$ApkPath = "$Root/app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path $ApkPath)) {
    Write-Host "[*] Building Android companion APK..." -ForegroundColor Cyan
    & "$Root/gradlew.bat" assembleDebug
}
if (-not (Test-Path $ApkPath)) {
    Write-Error "Failed to locate APK at $ApkPath"
}

function Build-ForArch($RustTarget, $Abi, $ZipName) {
    Write-Host "`n========================================================" -ForegroundColor Green
    Write-Host " Building Module for $Abi ($RustTarget) " -ForegroundColor Green
    Write-Host "========================================================" -ForegroundColor Green

    # Compile rust gateway
    Write-Host "[*] Compiling rclone-gateway for $RustTarget..." -ForegroundColor Cyan
    cargo build --release --target $RustTarget -p rclone-gateway --manifest-path "$Root/gateway/Cargo.toml"

    $OutDir = "$Root/dist/rclone-manager-$RustTarget"
    if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
    New-Item -ItemType Directory -Force -Path "$OutDir/bin" | Out-Null

    # 1. Copy Gateway binary
    Copy-Item "$Root/target/$RustTarget/release/rclone-gateway" "$OutDir/bin/rclone-gateway"

    # 2. Copy prebuilt rclone & fusermount3
    $PrebuiltDir = "$Root/magisk-module/prebuilt/$Abi"
    if (Test-Path $PrebuiltDir) {
        Write-Host "[*] Integrating prebuilt binaries from $PrebuiltDir..." -ForegroundColor Cyan
        Copy-Item "$PrebuiltDir/rclone" "$OutDir/bin/rclone"
        Copy-Item "$PrebuiltDir/fusermount3" "$OutDir/bin/fusermount3"
    } else {
        Write-Error "Prebuilt binaries not found for $Abi in $PrebuiltDir"
    }

    # 3. Copy module scripts
    Copy-Item "$Root/magisk-module/module.prop" "$OutDir/"
    Copy-Item "$Root/magisk-module/service.sh" "$OutDir/"
    Copy-Item "$Root/magisk-module/uninstall.sh" "$OutDir/"
    Copy-Item "$Root/magisk-module/post-fs-data.sh" "$OutDir/"
    Copy-Item "$Root/magisk-module/customize.sh" "$OutDir/"
    if (Test-Path "$Root/magisk-module/META-INF") {
        Copy-Item -Recurse "$Root/magisk-module/META-INF" "$OutDir/"
    }

    # 4. Copy companion APK
    Write-Host "[*] Integrating companion APK -> $OutDir/app.apk" -ForegroundColor Cyan
    Copy-Item $ApkPath "$OutDir/app.apk"

    # 5. Strictly remove system/ directory (No system mount)
    if (Test-Path "$OutDir/system") {
        Remove-Item -Recurse -Force "$OutDir/system"
    }

    # 6. Package flashable ZIP
    $TargetZip = "$Root/dist/rclone-manager-$RustTarget.zip"
    $AliasZip = "$Root/dist/$ZipName"
    if (Test-Path $TargetZip) { Remove-Item -Force $TargetZip }
    if (Test-Path $AliasZip) { Remove-Item -Force $AliasZip }

    Write-Host "[*] Packaging flashable zip to $AliasZip..." -ForegroundColor Cyan
    tar.exe -a -c -f $AliasZip -C $OutDir .
    Copy-Item $AliasZip $TargetZip

    Write-Host "✅ Successfully built: $AliasZip" -ForegroundColor Green
    Write-Host "   Size: $((Get-Item $AliasZip).Length / 1MB) MB" -ForegroundColor Gray
}

# Determine targets
$TargetsToBuild = @()
if ($Target -eq "all") {
    $TargetsToBuild += @{ RustTarget = "x86_64-linux-android"; Abi = "x86_64"; Zip = "rclone-manager-x86_64.zip" }
    $TargetsToBuild += @{ RustTarget = "aarch64-linux-android"; Abi = "arm64-v8a"; Zip = "rclone-manager-arm64.zip" }
} elseif ($Target -in @("x86_64", "x86_64-linux-android")) {
    $TargetsToBuild += @{ RustTarget = "x86_64-linux-android"; Abi = "x86_64"; Zip = "rclone-manager-x86_64.zip" }
} elseif ($Target -in @("arm64", "aarch64-linux-android")) {
    $TargetsToBuild += @{ RustTarget = "aarch64-linux-android"; Abi = "arm64-v8a"; Zip = "rclone-manager-arm64.zip" }
}

foreach ($t in $TargetsToBuild) {
    Build-ForArch -RustTarget $t.RustTarget -Abi $t.Abi -ZipName $t.Zip
}

Write-Host "`nAll build targets completed successfully!" -ForegroundColor Green
