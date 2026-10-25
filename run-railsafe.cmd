@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-railsafe-windows.ps1" %*
exit /b %ERRORLEVEL%
