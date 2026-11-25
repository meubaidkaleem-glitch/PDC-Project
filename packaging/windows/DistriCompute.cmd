@echo off
rem DistriCompute for Windows (docs/standalone.md). Starts the application with the Java runtime in this
rem folder: nothing else needs to be installed. Close this window or press Ctrl+C to stop it.
setlocal
title DistriCompute
if not defined DISTRICOMPUTE_HTTP_PORT set "DISTRICOMPUTE_HTTP_PORT=8080"
if not defined DISTRICOMPUTE_DATA_DIR set "DISTRICOMPUTE_DATA_DIR=%LOCALAPPDATA%\DistriCompute\data"

rem Already running? Then only open it in the browser.
curl.exe --silent --fail --max-time 3 "http://127.0.0.1:%DISTRICOMPUTE_HTTP_PORT%/api/v1/system/info" 2>nul | findstr /c:"distributed" >nul 2>&1
if not errorlevel 1 (
  echo DistriCompute is already running: http://localhost:%DISTRICOMPUTE_HTTP_PORT%/
  start "" "http://localhost:%DISTRICOMPUTE_HTTP_PORT%/"
  exit /b 0
)

if not exist "%DISTRICOMPUTE_DATA_DIR%" mkdir "%DISTRICOMPUTE_DATA_DIR%"
echo Starting DistriCompute. Your browser opens when it is ready; this takes a few seconds.
echo.
"%~dp0runtime\bin\java.exe" -jar "%~dp0app\districompute.jar" --spring.profiles.active=standalone "--districompute.data-dir=%DISTRICOMPUTE_DATA_DIR%" %*
set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" exit /b 0
if "%CODE%"=="130" exit /b 0
echo.
echo DistriCompute stopped with an error (exit code %CODE%). The messages above say why.
if not defined DISTRICOMPUTE_NO_PAUSE pause
exit /b %CODE%
