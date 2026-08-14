@echo off
title ApiSensitivities Demo Launcher

echo ======================================================
echo    ApiSensitivities Demo Launcher (backend :8080)
echo ======================================================
echo.

cd /d "%~dp0"

set "JAVA_CMD=java"
if exist "jre\bin\java.exe" (
    set "JAVA_CMD=%~dp0jre\bin\java.exe"
    echo [1/4] Using portable JRE
) else (
    where java >nul 2>&1
    if %errorlevel% neq 0 (
        echo [ERROR] Java not found.
        echo         Unzip portable JDK21 into "jre" folder under this dir,
        echo         or install JDK 21 on this machine.
        pause
        exit /b 1
    )
    echo [1/4] Using system Java
)

set "JAR="
for %%f in ("target\ApiSensitivities-*.jar") do set "JAR=%%f"
if not defined JAR (
    echo [ERROR] target\ApiSensitivities-*.jar not found.
    echo         Run the packaging bat on the dev machine first,
    echo         then copy this project incl. the target folder here.
    pause
    exit /b 1
)
echo [2/4] Found jar: %JAR%

echo [3/4] Freeing port 8080...
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }" >nul 2>&1

echo [4/4] Starting backend...
start "ApiSensitivities Backend" /D "%~dp0" "%JAVA_CMD%" -jar "%~dp0%JAR%"

echo Waiting for backend (max 30s)...
set /a tries=0
:waitloop
set /a tries+=1
if %tries% gtr 30 goto timeout
timeout /t 1 > nul
powershell -NoProfile -Command "try { $r=Invoke-WebRequest -Uri http://127.0.0.1:8080/actuator/health -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
if %errorlevel% neq 0 goto waitloop

echo.
echo ======================================================
echo  Backend + Admin UI ready: http://127.0.0.1:8080
echo    - Admin console (management UI)
echo    - Plugin gateway (same address)
echo.
echo  Load the extension (fixed ID ndlhcpcbahekidhmdcfkbmjdehiehglg):
echo    1. Open chrome://extensions, enable Developer mode
echo    2. Click "Load unpacked", select the plugin folder
echo    3. Click the extension icon, set gateway 127.0.0.1:8080,
echo       fill user id / department, click Save
echo    4. Open chat.deepseek.com and start the demo
echo.
echo  Enterprise managed mode (optional):
echo    Run plugin\policy\windows-gpo.reg as ADMIN, then refresh
echo    chrome://policy and check Status = OK. The config page
echo    fields will be locked to the pushed policy values.
echo ======================================================
pause > nul
exit /b 0

:timeout
echo [WARN] Backend not ready within 30s, check the backend window log.
pause > nul
exit /b 1
