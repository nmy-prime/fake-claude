@echo off
rem 从 .env 读取本地密钥后启动应用，避免把密钥写进任何会被提交的文件。
setlocal enabledelayedexpansion

if not exist "%~dp0.env" (
    echo [错误] 未找到 .env 文件。
    echo         请先执行： copy .env.example .env
    echo         然后在 .env 中填入 DEEPSEEK_API_KEY 和 GITHUB_PERSONAL_ACCESS_TOKEN
    exit /b 1
)

for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0.env") do (
    if not "%%~A"=="" set "%%~A=%%~B"
)

if "%DEEPSEEK_API_KEY%"=="" (
    echo [错误] .env 中缺少 DEEPSEEK_API_KEY
    exit /b 1
)

cd /d "%~dp0"
call mvnw.cmd spring-boot:run
