#!/usr/bin/env bash
# ============================================================
# SpringClaw · 阿里后端一键部署(2C2G 调优,Cloudflare Tunnel 前置)
# 用法: scp 本文件到阿里 → sudo bash deploy-ali.sh
# ============================================================
set -uo pipefail

# ====== ▼ 改这两项(默认是占位,必改) ▼ ======
REPO_URL="${REPO_URL:-https://github.com/你的用户名/springclaw.git}"   # ← 改成你的仓库地址
DEPLOY_DIR="${DEPLOY_DIR:-/opt/springclaw}"
# ===========================================

C() { printf '\033[36m%s\033[0m\n' "$1"; }
G() { printf '\033[32m%s\033[0m\n' "$1"; }
Y() { printf '\033[33m%s\033[0m\n' "$1"; }
R() { printf '\033[31m%s\033[0m\n' "$1"; }

# --- 0) 前置检查 ---
[ "$(id -u)" -eq 0 ] || { R "请用 root 或 sudo 运行(docker 需要)"; exit 1; }
command -v git  >/dev/null 2>&1 || { Y "装 git..."; (apt-get update && apt-get install -y git) || yum install -y git; }
command -v curl >/dev/null 2>&1 || { Y "装 curl..."; (apt-get install -y curl) || yum install -y curl; }

# --- 1) Docker ---
if ! command -v docker >/dev/null 2>&1; then
  Y ">>> 安装 Docker..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi
G "✓ Docker: $(docker --version)"

# --- 2) 代码 ---
if [ -d "$DEPLOY_DIR/.git" ]; then
  Y ">>> 更新代码($DEPLOY_DIR)..."
  git -C "$DEPLOY_DIR" pull --ff-only || true
else
  case "$REPO_URL" in
    *你的用户名*) R "请先编辑本脚本顶部的 REPO_URL 改成你的仓库地址!"; exit 1 ;;
  esac
  Y ">>> 克隆代码到 $DEPLOY_DIR ..."
  mkdir -p "$(dirname "$DEPLOY_DIR")"
  git clone "$REPO_URL" "$DEPLOY_DIR"
fi
cd "$DEPLOY_DIR" || { R "进不了 $DEPLOY_DIR"; exit 1; }

# --- 3) .env ---
if [ ! -f .env ]; then
  Y ">>> 生成 .env(从 .env.example)..."
  cp .env.example .env
  C "请用编辑器(nano/vi)改 $DEPLOY_DIR/.env,至少这几项,改完回来按回车:"
  echo "  MYSQL_ROOT_PASSWORD=<强密码>"
  echo "  RABBITMQ_PASSWORD=<强密码>"
  echo "  SPRINGCLAW_PRIMARY_API_KEY=<大模型 key;没有先留空走本地降级>"
  echo "  SPRINGCLAW_WEB_CORS_ALLOWED_ORIGINS=https://你的应用名.vercel.app"
  echo "  SPRINGCLAW_AUTH_COOKIE_SECURE=true"
  read -rp ">>> 编辑完 .env,按回车继续..."
fi

# --- 4) 构建+启动(2C2G 调优已在 Dockerfile/compose;构建可能 5-15min) ---
Y ">>> docker compose up -d --build  (2C2G 上较慢、会吃满 swap,正常;若 OOM 见脚本末尾 fallback)"
if ! docker compose up -d --build; then
  R "✗ 构建失败(大概率 2C2G 内存不足 OOM)。走 fallback:"
  C "在你本地(更强的机器)构建镜像并传过来:"
  echo "  本地: docker build -t springclaw:latest . && docker save springclaw:latest | gzip > /tmp/sc.tgz"
  echo "  传到阿里: scp /tmp/sc.tgz root@阿里:/tmp/"
  echo "  阿里本机: docker load < /tmp/sc.tgz  &&  docker compose up -d   # 不加 --build"
  exit 1
fi

# --- 5) 健康检查(给后端最长 120s) ---
Y ">>> 等待后端启动(最多 120s)..."
OK=0
for _ in $(seq 1 60); do
  if curl -sf http://127.0.0.1:18080/actuator/health >/dev/null 2>&1; then OK=1; break; fi
  sleep 2
done
if [ "$OK" = 1 ]; then
  G "✓ 后端 UP: $(curl -s http://127.0.0.1:18080/actuator/health)"
else
  R "✗ 120s 后健康检查仍未通过,看日志: docker compose logs -f app"
  exit 1
fi

C "============================================================"
G " 后端已在阿里跑起来(127.0.0.1:18080)。下一步:Cloudflare Tunnel"
C "  日志:  docker compose logs -f app"
C "  停:    docker compose down   |  重启: docker compose restart"
C "============================================================"
