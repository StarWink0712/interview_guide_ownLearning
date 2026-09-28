#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SCRIPT_DIR}/project-template"

if [[ $# -ne 1 ]]; then
  echo "用法: $0 /新项目/绝对路径"
  exit 1
fi

TARGET_DIR="$1"

if [[ ! -d "${SOURCE_DIR}" ]]; then
  echo "错误: 模板目录不存在: ${SOURCE_DIR}"
  exit 1
fi

mkdir -p "${TARGET_DIR}"

if [[ -n "$(find "${TARGET_DIR}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "错误: 目标目录不是空目录，安装已停止。"
  echo "目标目录: ${TARGET_DIR}"
  exit 1
fi

cp -R "${SOURCE_DIR}/." "${TARGET_DIR}/"

chmod +x \
  "${TARGET_DIR}/gradlew" \
  "${TARGET_DIR}/scripts/check-prerequisites.sh" \
  "${TARGET_DIR}/scripts/setup-local.sh" \
  "${TARGET_DIR}/scripts/start-deps.sh" \
  "${TARGET_DIR}/scripts/stop-deps.sh" \
  "${TARGET_DIR}/scripts/run-backend.sh" \
  "${TARGET_DIR}/scripts/verify.sh"

echo "初始化模板已安装到:"
echo "  ${TARGET_DIR}"
echo
echo "下一步:"
echo "  cd \"${TARGET_DIR}\""
echo "  ./scripts/setup-local.sh"
