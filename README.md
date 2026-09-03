# Smart Exam Platform

面向教师与学生的智能在线题库与组卷系统。项目采用前后端分离的 Java Web 架构，当前按单人、仅上午开发的实际条件推进。

## 核心流程

教师维护题库 → 创建并发布试卷 → 学生在线答题 → 系统批改客观题 → 教师批阅主观题 → 公布并查询成绩。

## 功能范围

- 管理员：用户管理和账号状态维护
- 教师：题库管理、手动组卷、考试发布、完整答卷查看、主观题阅卷和成绩排名统计
- 学生：参加考试、提交答卷、恢复未交卷答案、查看本人名次和已公布答卷
- 已确认增强：DeepSeek AI 辅助出题，生成草稿后由教师确认保存

完整范围、未选择增强项和排期见 [`plan.md`](plan.md)。

## 技术栈

| 部分 | 技术 |
|---|---|
| 前端 | Node.js 24 LTS、Vue 3.5、TypeScript 6、Vite 8 |
| 后端 | Java 21、Spring Boot 3.5、Maven 3.9 |
| 数据库 | MySQL 8.4 LTS、Flyway |
| 接口文档 | REST API Markdown 契约 |
| 本地运行 | 本机 Java + MySQL；Docker Compose 可选 |

精确依赖版本以 `backend/pom.xml` 和 `frontend/package-lock.json` 为准。

## 仓库现状

阶段 3“试卷、考试与答题”已完成，当前处于阶段 4“阅卷、成绩与核心验收”。现有系统支持三类账号登录、JWT 鉴权、教师题库、手动组卷和考试发布，以及学生答题、交卷、客观题判分和超时自动提交；阅卷、成绩回看、排名和管理员业务页面仍待实现。

| 文件 | 用途 |
|---|---|
| [`plan.md`](plan.md) | 功能范围、单人排期和验收标准 |
| [`docs/requirements-analysis.md`](docs/requirements-analysis.md) | 需求、业务流程和竞品参考 |
| [`docs/technical-design.md`](docs/technical-design.md) | 版本基线、目录、配置策略和架构边界 |
| [`docs/prototype.md`](docs/prototype.md) | 页面结构、核心页面草图和交互状态 |
| [`docs/database-design.md`](docs/database-design.md) | ER 关系、数据约束和迁移策略 |
| [`docs/api.md`](docs/api.md) | REST API 清单、响应和错误约定 |
| [`软件开发实践2_文字整理.md`](软件开发实践2_文字整理.md) | 课程要求原始整理 |
| [`AGENTS.md`](AGENTS.md) | AI 协作规则和项目现状台账 |

## 本机启动

要求安装 Java 21 或更高版本、Maven 3.9、Node.js 24 LTS 和 MySQL 8.4。Docker 不是必需项。

在仓库根目录执行一键初始化。脚本生成本地 `.env`、初始化数据库、安装依赖并验证两端：

```bash
./scripts/init-local.sh
```

脚本会提示输入 MySQL 管理员密码且不会保存该密码。初始化后启动后端：

```bash
set -a
source .env
set +a
mvn -f backend/pom.xml spring-boot:run
```

另开终端启动前端：

```bash
npm --prefix frontend run dev
```

访问前端 `http://localhost:5173`；后端健康检查为 `http://localhost:8080/api/health`。自定义数据库配置参照 `.env.example`，不要提交真实密码。

Flyway V2 会创建 `admin`、`teacher`、`student` 三个本地演示账号，初始密码均为 `ExamDemo123!`。这些账号只用于课程演示，非演示环境必须更换或禁用。

可选：使用 `docker compose up -d db` 启动 MySQL。
