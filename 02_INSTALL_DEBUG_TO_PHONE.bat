@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "02_INSTALL_BETA_TO_PHONE.bat"
exit /b %ERRORLEVEL%
