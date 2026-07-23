@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "scripts\SETUP_WINDOWS_ENV.bat"

set "ADB=adb.exe"
where adb.exe >nul 2>&1
if errorlevel 1 (
  if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
)

if not exist "%ADB%" (
  where "%ADB%" >nul 2>&1
  if errorlevel 1 (
    echo [FAILED] adb was not found in PATH or Android SDK Platform-Tools.
    pause
    exit /b 1
  )
)

if not exist "release\debug\QuietShield-Dormant-v0.1.0-alpha2-debug.apk" (
  echo [FAILED] Build the APK first using 01_BUILD_DEBUG.bat.
  pause
  exit /b 1
)

"%ADB%" devices
"%ADB%" install -r "release\debug\QuietShield-Dormant-v0.1.0-alpha2-debug.apk"
if errorlevel 1 (
  echo [FAILED] APK installation failed.
  pause
  exit /b 1
)

echo [PASS] QuietShield Dormant debug build installed.
pause
exit /b 0
