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

"%ADB%" devices
if errorlevel 1 (
  echo [FAILED] Unable to read connected Android devices.
  pause
  exit /b 1
)

"%ADB%" shell am start-foreground-service ^
  -n com.ajcoder.quietshield.dormant.debug/com.ajcoder.quietshield.dormant.service.DormantMonitorService ^
  -a com.ajcoder.quietshield.dormant.STOP_AUTOMATIC_CLOSING >nul 2>&1
ping 127.0.0.1 -n 2 >nul
"%ADB%" shell "pkill -f com.ajcoder.quietshield.dormant.engine.DormantShellMain >/dev/null 2>&1 || true"
"%ADB%" shell run-as com.ajcoder.quietshield.dormant.debug rm -f files/engine_token >nul 2>&1
"%ADB%" forward --remove tcp:47532 >nul 2>&1

if errorlevel 1 (
  echo [NOTICE] The phone may already be disconnected or the helper may already be stopped.
)

echo [PASS] Automatic closing was stopped.
echo You may now turn off USB debugging and Developer Options before opening a banking app.
pause
exit /b 0
