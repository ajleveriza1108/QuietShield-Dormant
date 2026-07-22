@echo off
setlocal
set DIR=%~dp0
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%DIR%scripts\FETCH_GRADLE_WRAPPER.ps1"
  if errorlevel 1 exit /b 1
)
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set and java.exe was not found. 1>&2
exit /b 1
:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1
:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
