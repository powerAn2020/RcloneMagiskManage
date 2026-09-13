# PowerShell Packaging Script for RcloneMagiskManage
# Builds gateway, integrates APK, and outputs flashable Magisk zip for x86_64 & arm64
param(
    [ValidateSet("all", "x86_64", "arm64", "aarch64-linux-android", "x86_64-linux-android")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Root = (Get-Item $PSScriptRoot).Parent.FullName

# 1. Ensure NDK & Rust Toolchain Environment
$NdkRoot = if ($env:NDK_ROOT) {
    $env:NDK_ROOT
} elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\ndk")) {
    (Get-ChildItem "$env:ANDROID_HOME\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1).FullName
} elseif ($env:ANDROID_SDK_ROOT -and (Test-Path "$env:ANDROID_SDK_ROOT\ndk")) {
    (Get-ChildItem "$env:ANDROID_SDK_ROOT\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1).FullName
} else {
    $null
}
$LlvmBin = if ($NdkRoot) { "$NdkRoot/toolchains/llvm/prebuilt/windows-x86_64/bin" } else { "" }
if ($LlvmBin -and (Test-Path "$LlvmBin/clang.exe")) {
    $env:PATH = "$LlvmBin;$env:PATH"
    $env:CC_x86_64_linux_android = "$LlvmBin/x86_64-linux-android34-clang.cmd"
    $env:CXX_x86_64_linux_android = "$LlvmBin/x86_64-linux-android34-clang++.cmd"
    $env:AR_x86_64_linux_android = "$LlvmBin/llvm-ar.exe"
    $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$LlvmBin/x86_64-linux-android34-clang.cmd"

    $env:CC_aarch64_linux_android = "$LlvmBin/aarch64-linux-android34-clang.cmd"
    $env:CXX_aarch64_linux_android = "$LlvmBin/aarch64-linux-android34-clang++.cmd"
    $env:AR_aarch64_linux_android = "$LlvmBin/llvm-ar.exe"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$LlvmBin/aarch64-linux-android34-clang.cmd"
}


# 2. Verify APK exists or build it
$ReleaseApk = "$Root/app/build/outputs/apk/release/app-release.apk"
$DebugApk = "$Root/app/build/outputs/apk/debug/app-debug.apk"
$ApkPath = if (Test-Path $ReleaseApk) {
    $ReleaseApk
} elseif (Test-Path $DebugApk) {
    $DebugApk
} else {
    Write-Host "[*] Building Android companion APK..." -ForegroundColor Cyan
    & "$Root/gradlew.bat" assembleDebug
    if (Test-Path $ReleaseApk) { $ReleaseApk } else { $DebugApk }
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
    $SubmoduleBinDir = "$Root/external/rclone-fuse3-magisk/magisk-rclone_$Abi/system/vendor/bin"
    if ((Test-Path "$PrebuiltDir/rclone") -and (Test-Path "$PrebuiltDir/fusermount3")) {
        Write-Host "[*] Integrating prebuilt binaries from $PrebuiltDir..." -ForegroundColor Cyan
        Copy-Item "$PrebuiltDir/rclone" "$OutDir/bin/rclone"
        Copy-Item "$PrebuiltDir/fusermount3" "$OutDir/bin/fusermount3"
    } elseif ((Test-Path "$SubmoduleBinDir/rclone") -and (Test-Path "$SubmoduleBinDir/fusermount3")) {
        Write-Host "[*] Integrating binaries from submodule build $SubmoduleBinDir..." -ForegroundColor Cyan
        Copy-Item "$SubmoduleBinDir/rclone" "$OutDir/bin/rclone"
        Copy-Item "$SubmoduleBinDir/fusermount3" "$OutDir/bin/fusermount3"
    } elseif ((Test-Path "$Root/scratch/unpacked_magisk_rclone/system/vendor/bin/rclone") -and ($Abi -eq "x86_64")) {
        Write-Host "[*] Integrating binaries from scratch cache..." -ForegroundColor Cyan
        Copy-Item "$Root/scratch/unpacked_magisk_rclone/system/vendor/bin/rclone" "$OutDir/bin/rclone"
        Copy-Item "$Root/scratch/unpacked_magisk_rclone/system/vendor/bin/fusermount3" "$OutDir/bin/fusermount3"
    } elseif ((Test-Path "$Root/scratch/unpacked_magisk_arm64/system/vendor/bin/rclone") -and ($Abi -in @("arm64-v8a", "arm64"))) {
        Write-Host "[*] Integrating arm64 binaries from scratch cache..." -ForegroundColor Cyan
        Copy-Item "$Root/scratch/unpacked_magisk_arm64/system/vendor/bin/rclone" "$OutDir/bin/rclone"
        Copy-Item "$Root/scratch/unpacked_magisk_arm64/system/vendor/bin/fusermount3" "$OutDir/bin/fusermount3"
    } else {
        Write-Warning "Prebuilt binaries not found for $Abi in $PrebuiltDir or $SubmoduleBinDir"
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

    # 6. Package flashable ZIP (Enforce standard UNIX forward slash '/' for KernelSU / Magisk compatibility)
    $TargetZip = "$Root/dist/rclone-manager-$RustTarget.zip"
    $AliasZip = "$Root/dist/$ZipName"
    if (Test-Path $AliasZip) { Remove-Item -Force $AliasZip }

    Write-Host "[*] Packaging flashable zip to $AliasZip..." -ForegroundColor Cyan
    $packedWithPython = $false
    if (Get-Command python -ErrorAction SilentlyContinue) {
        $pyCode = @"
import zipfile, os
src = r'$OutDir'
out = r'$AliasZip'
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src).replace('\\', '/')
            zinfo = zipfile.ZipInfo(rel)
            zinfo.create_system = 3
            if f.endswith('.sh') or 'bin' in rel.split('/') or f == 'update-binary':
                zinfo.external_attr = 0o100755 << 16
            else:
                zinfo.external_attr = 0o100644 << 16
            with open(full, 'rb') as fh:
                zf.writestr(zinfo, fh.read(), compress_type=zipfile.ZIP_DEFLATED)
"@
        python -c $pyCode
        if ($LASTEXITCODE -eq 0) { $packedWithPython = $true }
    }

    if (-not $packedWithPython) {
        $zipArchive = [System.IO.Compression.ZipFile]::Open($AliasZip, [System.IO.Compression.ZipArchiveMode]::Create)
        Get-ChildItem -Path $OutDir -Recurse -File | ForEach-Object {
            $relPath = $_.FullName.Substring($OutDir.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zipArchive, $_.FullName, $relPath) | Out-Null
        }
        $zipArchive.Dispose()
    }

    try {
        if (Test-Path $TargetZip) { Remove-Item -Force $TargetZip -ErrorAction SilentlyContinue }
        Copy-Item $AliasZip $TargetZip -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Warning "Notice: $TargetZip was locked by another process (e.g. 7-Zip). $AliasZip was successfully generated!"
    }

    Write-Host "✅ Successfully built: $AliasZip" -ForegroundColor Green
    Write-Host "   Size: $([math]::Round((Get-Item $AliasZip).Length / 1MB, 2)) MB" -ForegroundColor Gray
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
