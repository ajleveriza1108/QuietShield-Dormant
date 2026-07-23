@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PS51=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "PRECHECK=%~dp0scripts\PRECHECK_PS51.ps1"
set "SCRIPT=%~dp0scripts\ACTIVATE_AUTOMATIC_CLOSING_PS51.ps1"

if not exist "%PS51%" (
  echo [FAILED] Windows PowerShell 5.1 was not found.
  pause
  exit /b 1
)
if not exist "%PRECHECK%" (
  echo [FAILED] Parser precheck script was not found:
  echo %PRECHECK%
  pause
  exit /b 1
)
if not exist "%SCRIPT%" (
  echo [FAILED] Activation script was not found:
  echo %SCRIPT%
  pause
  exit /b 1
)

echo ============================================================
echo QuietShield Dormant Alpha 3 Automatic Closing
echo Windows PowerShell 5.1 compatibility preflight
echo ============================================================

"%PS51%" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%PRECHECK%" -ScriptPath "%SCRIPT%"
if errorlevel 1 (
  echo.
  echo [FAILED] Automatic Closing Setup was not started because the parser found an error.
  pause
  exit /b 1
)

"%PS51%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
set "EXITCODE=%ERRORLEVEL%"
echo.
if "%EXITCODE%"=="0" echo [PASS] Automatic Closing Setup completed.
if not "%EXITCODE%"=="0" echo [FAILED] Automatic Closing Setup did not complete.
pause
exit /b %EXITCODE%
