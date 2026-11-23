@echo off
rem Starts a worker from a properties file (docs/deployment.md). Not tested on Windows by the author.
rem   scripts\start-worker.cmd [config.properties] [more options...]
rem DISTRICOMPUTE_JAR selects the runnable jar; JAVA_OPTS adds JVM options.
setlocal
if "%DISTRICOMPUTE_JAR%"=="" set "DISTRICOMPUTE_JAR=%~dp0..\target\districompute-distributed-0.1.0-SNAPSHOT-all.jar"
set "CONFIG=%~1"
if "%CONFIG%"=="" set "CONFIG=%~dp0..\examples\config\worker.properties"
if not exist "%DISTRICOMPUTE_JAR%" (
  echo Runnable jar not found: %DISTRICOMPUTE_JAR%. Build it or set DISTRICOMPUTE_JAR. 1>&2
  exit /b 1
)
java %JAVA_OPTS% -jar "%DISTRICOMPUTE_JAR%" worker --config "%CONFIG%" %2 %3 %4 %5 %6 %7 %8 %9
