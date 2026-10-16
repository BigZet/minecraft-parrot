@echo off
chcp 65001 >nul
title Viewer Parrots - installer
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\install.ps1" %*
if errorlevel 1 (
    echo.
    echo Installation failed. See %LOCALAPPDATA%\ViewerParrots\install.log
    pause
)
