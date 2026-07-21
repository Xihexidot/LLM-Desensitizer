@echo off
chcp 65001 > nul
title ApiSensitivities 启动脚本

echo =======================================================
echo          ApiSensitivities 启动脚本
echo =======================================================
echo.

:: ====== 环境检查 ======
echo [检查] 环境依赖...

where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Java，请安装 JDK 21+
    pause
    exit /b 1
)

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Node.js，请安装 Node.js 18+
    pause
    exit /b 1
)

:: 检查 Maven Wrapper 是否存在
if not exist "mvnw.cmd" (
    echo [错误] 找不到 mvnw.cmd，请在项目根目录运行
    pause
    exit /b 1
)
echo    环境检查通过
echo.

:: ====== 检查配置文件 ======
if not exist "src\main\resources\application-local.properties" (
    echo [提示] 未找到 application-local.properties
    echo        将 application-local.example.properties 复制为
    echo        application-local.properties 并填入 API 密钥即可
    echo.
)

:: ====== 停止服务 ======
echo [1/5] 停止端口冲突的进程...

:: 仅停止占用 8080 端口的进程（后端），不影响其他 Java 程序
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 "') do (
    taskkill /F /PID %%a >nul 2>&1
)
if %errorlevel% equ 0 (
    echo    端口 8080 已释放
) else (
    echo    端口 8080 未被占用
)

:: 仅停止占用 5173 端口的进程（前端），不影响其他 Node 程序
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173 "') do (
    taskkill /F /PID %%a >nul 2>&1
)
if %errorlevel% equ 0 (
    echo    端口 5173 已释放
) else (
    echo    端口 5173 未被占用
)
echo.

:: ====== 启动后端 ======
echo [2/5] 启动后端服务...
start "Backend - Spring Boot" cmd /k "mvnw.cmd spring-boot:run"
echo    后端启动中... (端口 8080)
echo.

:: ====== 检查前端目录 ======
echo [3/5] 检查前端目录...
if not exist "front_end\" (
    echo [错误] 找不到 front_end 目录
    pause
    exit /b 1
)
cd front_end
echo    当前目录: %cd%
echo.

:: ====== 安装前端依赖 ======
echo [4/5] 安装前端依赖...
if not exist "node_modules\" (
    echo    正在执行 npm install...
    npm install
    if %errorlevel% neq 0 (
        echo.
        echo ========== npm install 失败 ==========
        echo 请手动执行以下命令：
        echo   cd front_end
        echo   npm install
        echo ======================================
        pause
        exit /b 1
    )
) else (
    echo    依赖已存在
)
echo.

:: ====== 启动前端 ======
echo [5/5] 启动前端服务...
start "Frontend - Vite" cmd /k "npm run dev"
cd ..

:: ====== 打开浏览器 ======
echo.
echo 等待后端启动...
timeout /t 8 > nul

start http://localhost:5173

echo.
echo =======================================================
echo  启动完成！
echo.
echo  后端: http://localhost:8080
echo  前端: http://localhost:5173
echo.
echo  首次使用前，请确保已配置 API 密钥：
echo    复制 application-local.example.properties 为
echo    application-local.properties 并填入密钥
echo =======================================================
echo 按任意键关闭此窗口...
pause > nul
