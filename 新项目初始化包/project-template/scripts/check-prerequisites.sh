#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "${PROJECT_DIR}/.env" ]]; then
  set -a
  source "${PROJECT_DIR}/.env"
  set +a
fi

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "缺少命令: ${command_name}"
    exit 1
  fi
}

check_port() {
  local port="$1"
  local service_name="$2"

  if command -v lsof >/dev/null 2>&1 \
      && lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "提示: ${service_name} 端口 ${port} 已被占用。"
  fi
}

require_command java
require_command docker

java_version="$(
  java -version 2>&1 \
    | awk -F '"' '/version/ { print $2; exit }'
)"
java_major="${java_version%%.*}"

if [[ "${java_major}" == "1" ]]; then
  java_major="$(echo "${java_version}" | cut -d. -f2)"
fi

if [[ "${java_major}" != "21" ]]; then
  echo "Java 版本不符合要求: ${java_version}"
  echo "需要 Java 21。"
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose 不可用，请安装 Docker Compose V2。"
  exit 1
fi

check_port "${SERVER_PORT:-8081}" "Spring Boot"
check_port "${POSTGRES_PORT:-5433}" "PostgreSQL"

echo "基础环境检查通过。"
echo "Java: ${java_version}"
docker --version
docker compose version
