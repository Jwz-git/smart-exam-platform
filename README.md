# Smart Exam Platform

面向教师与学生的智能在线题库与组卷系统。项目采用前后端分离的 Java Web 架构，由两人团队在课程实践周期内完成。

## 核心流程

教师维护题库 → 创建并发布试卷 → 学生在线答题 → 系统批改客观题 → 教师批阅主观题 → 公布并查询成绩。

## 计划功能

- 管理员：用户管理和账号状态维护
- 教师：题库管理、手动组卷、考试发布、主观题阅卷、成绩统计
- 学生：参加考试、提交答卷、查看成绩和答题记录
- 增强功能：规则自动组卷、错题本、统计图表、批量导入导出

增强功能仅在核心验收用例全部通过后开发。

## 技术栈

| 部分 | 技术 |
|---|---|
| 前端 | Node.js 24 LTS、Vue 3.5、TypeScript 6、Vite 8 |
| 后端 | Java 21、Spring Boot 3.5、Maven 3.9 |
| 数据库 | MySQL 8.4 LTS、Flyway |
| 接口文档 | OpenAPI / Swagger |
| 本地运行 | 本机 Java + MySQL；Docker Compose 可选 |

精确依赖版本以 `backend/pom.xml` 和 `frontend/package-lock.json` 为准。

## 仓库现状

阶段 2“认证、权限与题库”已完成，当前进入阶段 3“试卷、考试与答题”。现有系统支持三类账号登录、JWT 鉴权、教师知识点与四类题目管理及筛选，并提供登录和教师题库页面。

| 文件 | 用途 |
|---|---|
| [`plan.md`](plan.md) | 项目范围、架构、动态协作方式、排期和验收标准 |
| [`docs/requirements-analysis.md`](docs/requirements-analysis.md) | 9 月 1 日需求分析、核心用例和竞品参考 |
| [`docs/technical-design.md`](docs/technical-design.md) | 版本基线、目录、配置策略和架构边界 |
| [`docs/prototype.md`](docs/prototype.md) | 页面结构、核心页面草图和交互状态 |
| [`docs/database-design.md`](docs/database-design.md) | ER 关系、数据约束和迁移策略 |
| [`docs/api.md`](docs/api.md) | REST API 清单、响应和错误约定 |
| [`软件开发实践2_文字整理.md`](软件开发实践2_文字整理.md) | 课程要求原始整理 |
| [`AGENTS.md`](AGENTS.md) | AI 协作规则和项目现状台账 |

## 开发原则

- 先完成可演示的核心业务闭环，再开发增强功能。
- 后端执行身份、权限、参数和业务状态校验，不能只依赖前端限制。
- 每个阶段必须有可复核的完成证据，例如测试输出、接口响应或运行截图。
- 代码、文档和 `AGENTS.md` 中的项目现状应保持同步。

## 本机启动

要求安装 Java 21 或更高版本、Maven 3.9、Node.js 24 LTS 和 MySQL 8.4。Docker 不是必需项。

推荐在仓库根目录执行一键初始化。脚本会生成不提交到 Git 的 `.env` 随机 JWT 密钥、创建本地数据库、安装前端依赖、运行两端测试和构建，并执行 Flyway 迁移：

```bash
./scripts/init-local.sh
```

脚本会提示输入 MySQL 管理员密码。它不会把管理员密码写入文件。初始化后，终端一启动后端：

```bash
set -a
source .env
set +a
mvn -f backend/pom.xml spring-boot:run
```

终端二启动前端：

```bash
npm --prefix frontend run dev
```

后端健康检查地址为 `http://localhost:8080/api/health`，前端开发地址默认为 `http://localhost:5173`。若本地数据库配置不同，复制 `.env.example` 中相应变量到自己的终端环境或 `.env`，不要提交真实密码。

Flyway V2 会创建 `admin`、`teacher`、`student` 三个本地演示账号，初始密码均为 `ExamDemo123!`。这些账号只用于课程演示，非演示环境必须更换或禁用。

已安装 Docker 的成员也可用 `docker compose up -d db` 代替本机 MySQL，但这不是团队统一前置要求。
