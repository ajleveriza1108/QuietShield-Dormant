@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
echo ============================================================
echo QuietShield Dormant v0.2.0 Beta 1 Wireless Setup
echo ============================================================
echo.
echo Normal activation is completed inside the Android app.
echo.
echo 1. Install and open QuietShield Dormant.
echo 2. Tap the switch at the top right.
echo 3. Turn on Wireless Debugging in Developer options.
echo 4. Tap Pair device with pairing code.
echo 5. Enter the shown address and six-digit code in Dormant.
echo.
echo No USB cable is required for normal activation.
echo For development fallback only, run 04_USB_BACKUP_ACTIVATION.bat.
pause
exit /b 0
