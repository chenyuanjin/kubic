#!/usr/bin/env bash
# 后端 jar 的启停。跑在【宿主机】上,绑 127.0.0.1:8081,唯一的外部入口是 Caddy 的 :8090。
#
# 为什么不进 compose:进容器就得把 server.address 改成 0.0.0.0,
# 而那一行的注释解释了为什么它是 127.0.0.1。容器网络隔离也能达到同样效果,
# 但那是一层要人去验证的间接保证,宿主机回环是内核给的。
#
#   ./app.sh start | stop | status | logs
#
# 🔴 这个脚本不做健康探测之外的编排。它需要更多东西的那天(自动重启、开机自启、
#    多实例),要的就已经是 systemd unit 而不是更多的 shell —— 到那天再写,别现在猜。
set -euo pipefail
cd "$(dirname "$0")"

PID_FILE=./app.pid
LOG_FILE=./app.log
JAR=./kaodian-app.jar
PORT=8081

running() { [[ -f $PID_FILE ]] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; }

case "${1:-status}" in
start)
	running && { echo "已经在跑:PID $(cat $PID_FILE)"; exit 0; }
	[[ -f .env ]] || { echo "缺 deploy/.env(从 .env.example 拷)" >&2; exit 1; }
	[[ -f $JAR ]] || { echo "缺 $JAR(在本机 ./server/build.sh -q package 后 scp 上来)" >&2; exit 1; }
	set -a; . ./.env; set +a

	# 🔴 -Xmx256m 不是调优,是配额:这台机器一共剩 1.8G,MySQL 占了 420M、Redis 96M。
	# SerialGC 在 256M 堆 + 单核负载下比 G1 少几十 MB 元数据,而这里不需要 G1 的停顿保证。
	# 口令走环境变量(上面 set -a),不进命令行 —— 命令行是 ps 里所有人都能看的。
	nohup java \
		-Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC \
		-Dfile.encoding=UTF-8 \
		-jar "$JAR" \
		--server.port=$PORT \
		--kaodian.auth.trust-forwarded-for="${KAODIAN_TRUST_FORWARDED_FOR:-false}" \
		--kaodian.api.cors.allowed-origins="${KAODIAN_CORS_ORIGINS:-}" \
		>>"$LOG_FILE" 2>&1 &
	echo $! >$PID_FILE
	echo "启动中:PID $(cat $PID_FILE),日志 $LOG_FILE"

	for _ in $(seq 1 60); do
		if curl -fsS "http://127.0.0.1:$PORT/api/v1/coverage/summary" >/dev/null 2>&1; then
			echo "起来了:http://127.0.0.1:$PORT"; exit 0
		fi
		running || { echo "进程已退出,看 $LOG_FILE" >&2; exit 1; }
		sleep 2
	done
	echo "120 秒内没起来,看 $LOG_FILE" >&2; exit 1
	;;
stop)
	running || { echo "没在跑"; rm -f $PID_FILE; exit 0; }
	# 🔴 只杀自己那个 PID。绝不按名字杀 —— 这台机器上还有别人的 java/multica 进程。
	kill "$(cat $PID_FILE)"
	for _ in $(seq 1 30); do running || break; sleep 1; done
	running && kill -9 "$(cat $PID_FILE)" || true
	rm -f $PID_FILE
	echo "已停"
	;;
status)
	running && echo "在跑:PID $(cat $PID_FILE)" || echo "没在跑"
	curl -fsS -o /dev/null -w "本机 :$PORT → %{http_code}\n" \
		"http://127.0.0.1:$PORT/api/v1/coverage/summary" || true
	;;
logs) tail -n "${2:-80}" "$LOG_FILE" ;;
*) echo "用法:$0 {start|stop|status|logs [行数]}" >&2; exit 2 ;;
esac
