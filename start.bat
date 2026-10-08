@echo off
chcp 65001 >nul
setlocal enableextensions
cd /d "%~dp0"

set "VENV_DIR=%~dp0.venv"
set "PY=%VENV_DIR%\Scripts\python.exe"
set "SERVER=%~dp0ha_server.py"
set "CONFIG=%~dp0config.yaml"

echo ========================================
echo  CUKTECH BLE Server - Startup
echo ========================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found in PATH. Install Python 3.10+ first.
    goto :fail
)

if not exist "%PY%" (
    echo [1/3] Creating virtual environment...
    python -m venv "%VENV_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to create virtual environment.
        goto :fail
    )
    echo [2/3] Installing dependencies...
    "%PY%" -m pip install --upgrade pip
    "%PY%" -m pip install -e .
    if errorlevel 1 (
        echo [ERROR] Failed to install dependencies.
        goto :fail
    )
) else (
    echo [1/3] Virtual environment found.
    echo [2/3] Skipping dependency install ^(delete .venv to reinstall^).
)

if not exist "%CONFIG%" (
    echo [3/3] config.yaml missing, creating from config.yaml.example...
    copy /y "%~dp0config.yaml.example" "%CONFIG%" >nul
    echo       Edit config.yaml to set BLE/MQTT before first run.
) else (
    echo [3/3] config.yaml found.
)

echo.
echo Starting server... Press Ctrl+C to stop.
echo Web UI: http://localhost:18199/
echo.

set PYTHONUTF8=1
set CUKTECH_LAUNCHER_RESTART=1
:run_server
"%PY%" -u "%SERVER%"
set "SERVER_EXIT=%ERRORLEVEL%"
if "%SERVER_EXIT%"=="75" goto :run_server
exit /b %SERVER_EXIT%

:fail
echo.
echo Startup aborted.
exit /b 1
