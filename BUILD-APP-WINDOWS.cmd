@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0packaging\scripts\build-windows.ps1" %*
echo.
echo If build completed, check the dist\windows folder.
pause
