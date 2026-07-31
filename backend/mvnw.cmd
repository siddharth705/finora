@REM ----------------------------------------------------------------------------
@REM Maven Wrapper startup script for Windows, adapted from the standard Apache
@REM Maven Wrapper distribution (https://maven.apache.org/wrapper/).
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "BASEDIR=%~dp0"
set "WRAPPER_JAR=%BASEDIR%.mvn\wrapper\maven-wrapper.jar"
set "WRAPPER_PROPERTIES=%BASEDIR%.mvn\wrapper\maven-wrapper.properties"
set WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

if "%JAVA_HOME%"=="" (
  set "JAVA_CMD=java"
) else (
  set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

if not exist "%WRAPPER_JAR%" (
  echo Maven wrapper jar not found - downloading per %WRAPPER_PROPERTIES% ...
  for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
    if "%%A"=="wrapperUrl" set "WRAPPER_URL=%%B"
  )
  powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
)

"%JAVA_CMD%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%BASEDIR%" %WRAPPER_LAUNCHER% %*
