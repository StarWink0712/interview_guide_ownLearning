#!/bin/zsh

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
URL="http://127.0.0.1:5173/"

echo "========================================"
echo "AI Interview Platform - 前端启动脚本"
echo "========================================"
echo ""

if ! command -v npm >/dev/null 2>&1; then
  echo "未检测到 npm，请先安装 Node.js。"
  echo "按回车键关闭窗口..."
  read
  exit 1
fi

if [ ! -d "$FRONTEND_DIR" ]; then
  echo "未找到 frontend 目录：$FRONTEND_DIR"
  echo "请确认脚本放在项目根目录。"
  echo "按回车键关闭窗口..."
  read
  exit 1
fi

cd "$FRONTEND_DIR"

if [ ! -d "node_modules" ]; then
  echo "首次启动：正在安装前端依赖..."
  npm install
fi

echo "正在启动前端服务：$URL"
echo "浏览器会自动打开。停止服务时，请在这个窗口按 Ctrl + C。"
echo ""

npm run dev -- --host 127.0.0.1 --port 5173 --open "$URL"
