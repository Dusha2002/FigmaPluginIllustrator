@ECHO OFF
SETLOCAL

SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
SET "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"
SET "WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain"

IF NOT EXIST "%WRAPPER_PROPERTIES%" (
  ECHO Cannot find %WRAPPER_PROPERTIES% >&2
  EXIT /B 1
)

IF NOT EXIST "%WRAPPER_JAR%" (
  FOR /F "tokens=1,* delims==" %%A IN ('findstr /R "^wrapperUrl=" "%WRAPPER_PROPERTIES%"') DO (
    IF "%%A"=="wrapperUrl" SET "WRAPPER_URL=%%B"
  )
  IF NOT DEFINED WRAPPER_URL (
    ECHO wrapperUrl is not set in %WRAPPER_PROPERTIES% >&2
    EXIT /B 1
  )
  MKDIR "%~dp0\.mvn\wrapper" 2>NUL
  ECHO Downloading Maven Wrapper from %WRAPPER_URL% >&2
  POWERSHELL -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'" || (
    ECHO Failed to download Maven Wrapper JAR. Please download it manually. >&2
    EXIT /B 1
  )
)

IF NOT DEFINED JAVA_HOME (
  SET "JAVA_CMD=java"
) ELSE (
  SET "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_CMD%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" %WRAPPER_LAUNCHER% %*
EXIT /B %ERRORLEVEL%
