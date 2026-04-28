@echo off
chcp 65001 >nul
title X-Rav · launcher (x64)

rem === Self-elevate ===
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Запрашиваю права администратора ^(нужны для Wintun^)...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"

if not exist "%~dp0x64\X-Rav.exe" (
    echo [ОШИБКА] Не найден файл "%~dp0x64\X-Rav.exe"
    echo Возможно, Defender удалил .exe или папку x64. Сделай так:
    echo   1^) ПКМ по Setup-Defender.bat -^> Запуск от администратора
    echo   2^) Распакуй архив заново
    echo   3^) Запусти этот .bat снова
    pause
    exit /b 1
)

echo Снимаю Mark-of-the-Web (MOTW) с файлов...
powershell -NoProfile -Command "try { Get-ChildItem -Path '%~dp0x64' -Recurse -File | Unblock-File -ErrorAction SilentlyContinue } catch {}"

echo Запускаю X-Rav (x64)...
echo.
"%~dp0x64\X-Rav.exe"
set EXITCODE=%errorlevel%

if %EXITCODE% neq 0 (
    echo.
    echo === Приложение завершилось с кодом %EXITCODE% ===
    echo.
    echo Возможные причины:
    echo  - Defender удалил xray.exe / sing-box.exe при распаковке в %%APPDATA%%\X-Rav\tools.
    echo    Решение: запусти Setup-Defender.bat (от админа) и попробуй снова.
    echo  - Антивирус заблокировал Wintun.dll.
    echo  - Не хватает Visual C++ Redistributable (маловероятно — всё embedded).
    echo.
    echo Подробный лог: %%APPDATA%%\X-Rav\logs\
    echo.
    pause
)
