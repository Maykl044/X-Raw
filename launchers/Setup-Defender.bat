@echo off
chcp 65001 >nul
title X-Rav · настройка Defender (one-time)

rem === Self-elevate ===
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Нужны права администратора. Перезапускаю с UAC...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
echo === X-Rav: добавление исключений Microsoft Defender ===
echo.
echo Это нужно сделать ОДИН раз, чтобы Defender не удалял xray.exe / sing-box.exe.
echo .exe не подписан сертификатом, поэтому Defender (эвристически) считает его подозрительным.
echo.

set "INSTALLDIR=%~dp0"
if "%INSTALLDIR:~-1%"=="\" set "INSTALLDIR=%INSTALLDIR:~0,-1%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$d='%INSTALLDIR%';" ^
  "Write-Host '[+] Папка установки:' $d -ForegroundColor Cyan;" ^
  "Add-MpPreference -ExclusionPath $d -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionPath ($env:APPDATA + '\X-Rav') -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionPath ($env:LOCALAPPDATA + '\Temp\.net') -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionProcess 'X-Rav.exe' -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionProcess 'xray.exe' -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionProcess 'sing-box.exe' -ErrorAction SilentlyContinue;" ^
  "Add-MpPreference -ExclusionProcess 'hev-socks5-tunnel.exe' -ErrorAction SilentlyContinue;" ^
  "Write-Host '[OK] Исключения добавлены:' -ForegroundColor Green;" ^
  "Write-Host '    -' $d;" ^
  "Write-Host '    -' ($env:APPDATA + '\X-Rav');" ^
  "Write-Host '    -' ($env:LOCALAPPDATA + '\Temp\.net');" ^
  "Write-Host '    - процессы: X-Rav.exe, xray.exe, sing-box.exe, hev-socks5-tunnel.exe';" ^
  "Write-Host '';" ^
  "Write-Host 'Восстанавливаю файлы из карантина (если были удалены)...' -ForegroundColor Cyan;" ^
  "try { Start-Process -FilePath 'C:\ProgramData\Microsoft\Windows Defender\Platform\*\MpCmdRun.exe' -ArgumentList '-Restore','-All' -Wait -ErrorAction SilentlyContinue } catch {}"

echo.
echo === Готово ===
echo.
echo Теперь:
echo   1) Распакуй архив заново (если ты удалял .exe — Defender их съел)
echo   2) Двойной клик по «Запустить-x64.bat» (или -x86 для 32-бит Windows)
echo.
pause
