# Setup toolchain environment variables for RcloneMagiskManage
# Usage: . .\scripts\setup-env.ps1

# Fallback detection for Java 17+ if JAVA_HOME is not set or points to legacy Java
if (-not $env:JAVA_HOME -or ($env:JAVA_HOME -match "jdk8")) {
    $javaCandidates = @(
        "C:\Development\Java\jdk-17.0.12",
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($path in $javaCandidates) {
        if (Test-Path $path) {
            $env:JAVA_HOME = $path
            break
        }
    }
}

# Fallback detection for ANDROID_HOME / ANDROID_SDK_ROOT
if (-not $env:ANDROID_HOME) {
    $sdkCandidates = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "C:\Development\JetBrains\AndroidSDK",
        "C:\Android\sdk"
    )
    foreach ($path in $sdkCandidates) {
        if (Test-Path $path) {
            $env:ANDROID_HOME = $path
            break
        }
    }
}
if ($env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

# Fallback detection for NDK_ROOT
if (-not $env:NDK_ROOT -and $env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\ndk")) {
    $foundNdk = Get-ChildItem -Path "$env:ANDROID_HOME\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($foundNdk) {
        $env:NDK_ROOT = $foundNdk.FullName
    }
}

# NDK LLVM toolchain for Rust Android cross-compilation
if ($env:NDK_ROOT) {
    $llvm = "$env:NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64\bin"
    if (Test-Path $llvm) {
        $env:CC_x86_64_linux_android = "$llvm\x86_64-linux-android34-clang.cmd"
        $env:CXX_x86_64_linux_android = "$llvm\x86_64-linux-android34-clang++.cmd"
        $env:AR_x86_64_linux_android = "$llvm\llvm-ar.exe"
        $env:CC_aarch64_linux_android = "$llvm\aarch64-linux-android34-clang.cmd"
        $env:CXX_aarch64_linux_android = "$llvm\aarch64-linux-android34-clang++.cmd"
        $env:AR_aarch64_linux_android = "$llvm\llvm-ar.exe"
    }
}

# Prepend platform-tools (adb) and Java to PATH
$extraPaths = @()
if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools")) {
    $extraPaths += "$env:ANDROID_HOME\platform-tools"
}
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin")) {
    $extraPaths += "$env:JAVA_HOME\bin"
}
if ($extraPaths.Count -gt 0) {
    $env:PATH = ($extraPaths -join ";") + ";$env:PATH"
}

Write-Host "Toolchain environment variables configured:" -ForegroundColor Green
Write-Host "  JAVA_HOME: $env:JAVA_HOME"
Write-Host "  ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "  NDK_ROOT: $env:NDK_ROOT"
Write-Host "  ADB: $(Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)"
Write-Host "  JAVA: $(Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)"
