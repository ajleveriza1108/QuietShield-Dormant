@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
call "scripts\SETUP_WINDOWS_ENV.bat"

set "ADB=adb.exe"
where adb.exe >nul 2>&1
if errorlevel 1 (
  if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
)

where "%ADB%" >nul 2>&1
if errorlevel 1 if not exist "%ADB%" (
  echo [FAILED] adb was not found in PATH or Android SDK Platform-Tools.
  pause
  exit /b 1
)

set "APK=release\beta\QuietShield-Dormant-v0.2.0-beta1-r3.apk"
if not exist "%APK%" (
  echo [FAILED] Build the beta APK first using 01_BUILD_BETA.bat.
  pause
  exit /b 1
)

echo ============================================================
echo QuietShield Dormant v0.2.0 Beta 1 R3 Phone Installation
echo ============================================================
"%ADB%" devices
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [FAILED] APK installation failed.
  pause
  exit /b 1
)

echo [PASS] QuietShield Dormant Beta 1 R3 installed.
echo Open the app and use Wireless setup for normal automatic closing.
pause
exit /b 0
