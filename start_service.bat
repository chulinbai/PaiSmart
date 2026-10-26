@echo off
chcp 65001
echo ==============================================
echo        一键后台启动所有服务（无窗口+分日志）
echo ==============================================
echo.

:: ==================== 本地 Prometheus + 本地 Grafana 监控 ====================
:: 本地 Prometheus 抓取 Actuator，本地 Grafana 读取 Prometheus:9090。
set "MANAGEMENT_SERVER_ADDRESS=0.0.0.0"
set "MANAGEMENT_SERVER_PORT=8082"
set "PAISMART_PROMETHEUS_URL=http://localhost:9090"
set "PAISMART_GRAFANA_URL=http://localhost:3000"
echo [Monitor] 本地 Prometheus 地址：%PAISMART_PROMETHEUS_URL%
echo [Monitor] 本地 Grafana 地址：%PAISMART_GRAFANA_URL%

:: ==================== 日志目录（自动创建） ====================
set LOG_DIR=F:\code\java\学习\PaiSmart\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:: ==================== 启动命令（后台 + 输出日志） ====================

:: 1. Redis
start /b "" cmd /c "cd /d D:\ProSoftware\Redis\Redis-7.4.0-Windows-x64-msys2-with-Service && redis-server.exe redis.conf" >> "%LOG_DIR%\redis.log" 2>&1
timeout /t 1 /nobreak >nul
tasklist | findstr "redis-server.exe" >nul
if %errorlevel% equ 0 (echo [Redis] ✅ 启动成功) else (echo [Redis] ❌ 启动失败)

:: 2. MinIO
:: OSS文件存储
start /b "" cmd /c "minio server F:\code\java\学习\PaiSmart\data" >> "%LOG_DIR%\minio.log" 2>&1
timeout /t 1 /nobreak >nul
tasklist | findstr "minio.exe" >nul
if %errorlevel% equ 0 (echo [MinIO] ✅ 启动成功) else (echo [MinIO] ❌ 启动失败)

:: 3. Elasticsearch
:: 搜索
:: D:\ProSoftware\elasticsearch-8.10.0\bin\elasticsearch.bat
@REM start /b "" cmd /c "D:\ProSoftware\elasticsearch-8.10.0\bin\elasticsearch.bat" >> "%LOG_DIR%\elasticsearch.log" 2>&1


:: 4. Kafka KRaft
:: 消息
:: ~/software/kafka/bin/kafka-server-start.sh ~/software/kafka/config/kraft/server.properties
@REM set "KAFKA_HOME=D:\ProSoftware\kafka"
@REM if not exist "%KAFKA_HOME%\bin\windows\kafka-server-start.bat" (
@REM 	echo [Kafka] ❌ 未找到 Kafka 启动脚本：%KAFKA_HOME%
@REM ) else (
@REM 	start "" /b cmd /c ""%KAFKA_HOME%\bin\windows\kafka-server-start.bat" "%KAFKA_HOME%\config\kraft\server.properties" >> "%LOG_DIR%\kafka.log" 2>&1"
@REM 	timeout /t 6 /nobreak >nul
@REM 	netstat -ano | findstr ":9092" >nul
@REM 	if %errorlevel% equ 0 (echo [Kafka] ✅ 启动成功) else (echo [Kafka] ❌ 启动失败)
@REM )

:: 5. Ollama
start /b "" cmd /c "ollama serve" >> "%LOG_DIR%\ollama.log" 2>&1
timeout /t 1 /nobreak >nul
tasklist | findstr "ollama.exe" >nul
if %errorlevel% equ 0 (echo [Ollama] ✅ 启动成功) else (echo [Ollama] ❌ 启动失败)


:: 6. MySQL (服务模式，无需日志)
net start mysql84

echo.
echo ✅ 所有服务已【后台启动】完成！
echo 📝 日志目录：%LOG_DIR%
echo.
pause

:: 7. 测压Mock llm
:: cd /d F:\code\java\学习\PaiSmart\scr\test && conda activate fastapi && python mock_llm.py

:: 8. 启动 Prometheus
:: cd /d D:\ruanjian\prometheus-3.13.2.windows-amd64\prometheus-3.13.2.windows-amd64 && prometheus.exe --config.file="prometheus.yml"

:: 9. 启动 Grafana
:: cd /d D:\ruanjian\GrafanaLabs\grafana\bin && grafana.exe server