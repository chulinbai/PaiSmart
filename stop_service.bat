@echo off
chcp 65001
echo ==============================================
echo          一键停止所有本地服务
echo ==============================================
echo.

:: 停止 Redis
@REM redis-cli shutdown
@REM echo [Redis] ✅ 已停止

:: 停止 MinIO
@REM minio.exe stop
@REM echo [MinIO] ✅ 已停止

:: 停止 Elasticsearch
@REM net stop elasticsearch-service-x64
@REM echo [Elasticsearch] ✅ 已停止

:: 停止 Kafka（Java进程，只关你启动的）
@REM cd /d D:\ProSoftware\kafka
@REM D:\ProSoftware\kafka\bin\windows\kafka-server-stop.bat >nul 2>&1
@REM echo [Kafka] ✅ 已停止

:: 停止 Ollama
@REM taskkill /f /im ollama.exe
@REM echo [Ollama] ✅ 已停止

:: 停止 MySQL
net stop mysql84
echo [MySQL84] ✅ 已停止

echo.
echo ==============================================
echo ✅ 所有服务已全部安全关闭！
echo ==============================================
pause