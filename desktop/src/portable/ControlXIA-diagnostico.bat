@echo off
rem Abre ControlXIA con una consola visible para ver errores.
cd /d "%~dp0"
"runtime\bin\java.exe" -Xmx512m -Dfile.encoding=UTF-8 -Dcontrolxia.launcher="%~dp0ControlXIA.vbs" -cp "lib\*" com.controlxia.desktop.MainKt
pause
