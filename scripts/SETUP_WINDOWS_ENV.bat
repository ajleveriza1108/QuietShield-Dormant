@echo off
setlocal EnableExtensions

if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jre\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"

endlocal & set "JAVA_HOME=%JAVA_HOME%" & set "ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT%" & set "ANDROID_HOME=%ANDROID_HOME%" & set "PATH=%PATH%"
exit /b 0
