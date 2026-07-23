@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
echo ============================================================
echo QuietShield Dormant Alpha 4 Wireless Activation
echo ============================================================
echo.
echo Wireless activation is now completed inside the Android app.
echo.
echo 1. Install and open QuietShield Dormant.
echo 2. Tap the switch at the top right.
echo 3. Open Developer options and turn on Wireless Debugging.
echo 4. Tap Pair device with pairing code.
echo 5. Enter the pairing port and six-digit code in Dormant.
echo.
echo A Windows computer and USB cable are not required.
echo.
echo For the optional USB backup method, run:
echo 04_USB_BACKUP_ACTIVATION.bat
pause
exit /b 0
