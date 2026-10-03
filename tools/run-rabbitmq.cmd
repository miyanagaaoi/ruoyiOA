@echo off
rem ============================================================================
rem  以「绿色/便携」方式启动 RabbitMQ（不注册 Windows 服务）
rem   - ERLANG_HOME 指向静默安装的 Erlang/OTP 26.2.5.3
rem   - RABBITMQ_BASE 指向工程内 .cache，避免污染 %APPDATA%
rem  被 start-rabbitmq.ps1 通过 WMI 拉起，从而完全脱离调用方的控制台/管道句柄。
rem ============================================================================
setlocal
set "ERLANG_HOME=C:\Tools\erl-26.2.5.3"
set "PATH=%ERLANG_HOME%\bin;%PATH%"
set "RABBITMQ_BASE=H:\dsh\ruoyiOA\.cache\rabbitmq"
if not exist "%RABBITMQ_BASE%" mkdir "%RABBITMQ_BASE%"
cd /d "C:\Tools\rabbitmq_server-3.12.14"
call sbin\rabbitmq-server.bat > "H:\dsh\ruoyiOA\logs\rabbitmq.log" 2>&1
