@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "01_BUILD_BETA.bat"
exit /b %ERRORLEVEL%
