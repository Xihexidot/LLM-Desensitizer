@echo off
title ApiSensitivities Package (fat jar + admin UI)

echo ======================================================
echo    ApiSensitivities package - fat jar + admin UI
echo ======================================================
echo.

cd /d "%~dp0"

where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found, install JDK 21+ first.
    pause
    exit /b 1
)

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found, install Node 18+ first.
    pause
    exit /b 1
)

echo [1/4] Building frontend (npm install + npm run build)...
cd front_end
if not exist "node_modules\" (
    call npm install
    if %errorlevel% neq 0 (
        echo [ERROR] npm install failed.
        pause
        exit /b 1
    )
)
call npm run build
if %errorlevel% neq 0 (
    echo [ERROR] npm run build failed.
    pause
    exit /b 1
)
cd ..

echo [2/4] Copying frontend dist into backend static folder...
if exist "src\main\resources\static\assets" rmdir /s /q "src\main\resources\static\assets"
del /q "src\main\resources\static\index.html" 2>nul
del /q "src\main\resources\static\sample_sensitive_30k.txt" 2>nul
del /q "src\main\resources\static\vite.svg" 2>nul
xcopy /e /y /q "front_end\dist" "src\main\resources\static" >nul

echo [3/4] Packaging fat jar (tests skipped)...
call mvnw.cmd "-Dmaven.test.skip=true" clean package
if %errorlevel% neq 0 (
    echo [ERROR] Build failed, check log above.
    pause
    exit /b 1
)

echo.
echo [4/4] Done!
echo   Output: target\ApiSensitivities-0.0.1-SNAPSHOT.jar
echo.
echo   The jar now serves BOTH:
echo     - Admin UI    : http://localhost:8080
echo     - Backend API : http://localhost:8080
echo.
echo   Demo bundle:
echo     1. target\ApiSensitivities-0.0.1-SNAPSHOT.jar
echo     2. portable JDK21 unzipped to "jre" under this dir (optional)
echo     3. plugin folder (browser extension)
echo     4. Demo-launcher bat
echo.
pause > nul
