@echo off
setlocal
pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-server.ps1" %*
exit /b %ERRORLEVEL%
