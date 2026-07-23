@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "scripts\SETUP_WINDOWS_ENV.bat"

if not defined JAVA_HOME (
  where java.exe >nul 2>&1
  if errorlevel 1 (
    echo [FAILED] Java was not found. Android Studio JBR was checked automatically.
    pause
    exit /b 1
  )
)

call gradlew.bat --no-daemon clean test lint assembleDebug
if errorlevel 1 goto failed
if not exist "app\build\outputs\apk\debug\app-debug.apk" goto failed
if not exist "release\debug" mkdir "release\debug"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "release\debug\QuietShield-Dormant-v0.1.0-alpha3-r4-debug.apk" >nul

echo.
echo [PASS] release\debug\QuietShield-Dormant-v0.1.0-alpha3-r4-debug.apk
pause
exit /b 0

:failed
echo.
echo [FAILED] Debug validation or build failed.
pause
exit /b 1
