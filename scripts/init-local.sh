#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$project_dir/.env"

for command_name in mysql mvn node npm openssl; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "缺少命令: $command_name" >&2
        exit 1
    fi
done

if [[ ! -f "$env_file" ]]; then
    cp "$project_dir/.env.example" "$env_file"
    jwt_secret="$(openssl rand -hex 32)"
    sed -i.bak "s/^JWT_SECRET=.*/JWT_SECRET=$jwt_secret/" "$env_file"
    rm -f "$env_file.bak"
    echo "已创建本机 .env，并生成随机 JWT 密钥。"
elif grep -Eq '^JWT_SECRET=replace-' "$env_file" || ! grep -Eq '^JWT_SECRET=.{32,}$' "$env_file"; then
    jwt_secret="$(openssl rand -hex 32)"
    printf '\nJWT_SECRET=%s\n' "$jwt_secret" >> "$env_file"
    echo "已向现有 .env 补充随机 JWT 密钥。"
else
    echo "保留现有 .env。"
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

echo "请输入 MySQL 管理员密码以创建本地数据库和开发账号："
mysql -uroot -p < "$project_dir/database/bootstrap-local.sql"

echo "安装前端锁定依赖并执行前端验证……"
npm --prefix "$project_dir/frontend" ci
npm --prefix "$project_dir/frontend" run type-check
npm --prefix "$project_dir/frontend" run lint
npm --prefix "$project_dir/frontend" run test:unit -- --run
npm --prefix "$project_dir/frontend" run build

echo "执行后端测试和 Flyway 数据库迁移……"
mvn -f "$project_dir/backend/pom.xml" test
mvn -f "$project_dir/backend/pom.xml" spring-boot:run \
    -Dspring-boot.run.arguments=--spring.main.web-application-type=none

echo "初始化完成。演示账号：admin、teacher、student；初始密码：ExamDemo123!"
echo "运行后端：set -a; source .env; set +a; mvn -f backend/pom.xml spring-boot:run"
echo "运行前端：npm --prefix frontend run dev"
