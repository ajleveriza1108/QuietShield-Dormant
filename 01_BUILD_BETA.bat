@echo off
setlocal EnableExtensions DisableDelayedExpansion
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

echo ============================================================
echo QuietShield Dormant v0.2.0 Beta 1 R2 Build
echo Optimized, installable beta APK
echo ============================================================

call gradlew.bat --no-daemon clean test lintBeta assembleBeta
if errorlevel 1 goto failed

set "BUILT_APK=app\build\outputs\apk\beta\app-beta.apk"
set "RELEASE_DIR=release\beta"
set "RELEASE_APK=%RELEASE_DIR%\QuietShield-Dormant-v0.2.0-beta1-r2.apk"

if not exist "%BUILT_APK%" (
  echo [FAILED] Expected beta APK was not produced: %BUILT_APK%
  goto failed
)
if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
copy /y "%BUILT_APK%" "%RELEASE_APK%" >nul
if errorlevel 1 goto failed

for %%F in ("%RELEASE_APK%") do set "APK_BYTES=%%~zF"
set /a APK_MB=(APK_BYTES+1048575)/1048576

echo.
echo [PASS] %RELEASE_APK%
echo [INFO] APK size: approximately %APK_MB% MB
if %APK_MB% GTR 22 echo [WARNING] APK is over 22 MB and requires size review.
if %APK_MB% GTR 18 if %APK_MB% LEQ 22 echo [NOTICE] APK is above the 18 MB target.
pause
exit /b 0

:failed
echo.
echo [FAILED] Beta tests, lint, or APK build failed.
pause
exit /b 1
