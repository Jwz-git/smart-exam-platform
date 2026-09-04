# Smart Exam Platform

面向教师与学生的智能在线题库与组卷系统。项目采用前后端分离的 Java Web 架构，由一人完成；设计、开发与测试时间到 9 月 5 日结束，9 月 6 日为集成测试、系统实施与回报。

## 核心流程

教师维护题库 → 创建并发布试卷 → 学生在线答题 → 系统批改客观题 → 教师批阅主观题 → 公布并查询成绩。

## 功能范围

- 管理员：用户管理和账号状态维护
- 教师：题库管理（五种题型、关键词标签、启用停用）、手动组卷、考试发布、完整答卷查看、主观题阅卷、成绩排名统计，以及统计分析（题库分布、成绩分布、逐题正确率）和只读系统设置
- 学生：参加考试、提交答卷、恢复未交卷答案、查看本人名次和已公布答卷
- 已确认增强：AI 辅助出题（自定义服务商，兼容 OpenAI 与 Anthropic 协议），生成草稿后由教师确认保存

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

MVP 闭环已全部打通：三类账号登录与 JWT 鉴权、教师题库、手动组卷、考试发布、学生答题与刷新恢复、交卷与客观题判分、超时自动交卷、主观题阅卷、成绩汇总与公布、竞赛排名、学生本人查分，以及管理员的用户查询、新增和启用停用。增强功能已实现三项：AI 辅助出题（OpenAI 兼容与 Anthropic 两种协议，已在真实服务商上联调通过）、统计分析（题库分布、成绩分布、逐题正确率）和只读系统设置；侧栏不再有未开放入口。

2026-09-04 完成最新版 Chrome 完整走查，留存 27 张截图并修复走查中发现的 6 个界面缺陷；全部验收数字已在真实 MySQL 上复现；项目报告与思政报告正文已完成。剩余待办只有两项需要特定环境：MySQL 8.4 空库跑一次 Flyway、从干净环境按本文档验证启动。

| 文件 | 用途 |
|---|---|
| [`plan.md`](plan.md) | 功能范围、单人排期和验收标准 |
| [`docs/project-report.md`](docs/project-report.md) | **项目报告**：背景与竞品、需求、设计、系统演示（27 张截图）、运行结果分析、心得体会 |
| [`docs/ideological-report.md`](docs/ideological-report.md) | **思政报告**：科技自立、工匠精神、科技诚信、AI 边界与数据责任（约 5100 字，12 篇参考文献） |
| [`docs/requirements-analysis.md`](docs/requirements-analysis.md) | 需求、业务流程和竞品参考 |
| [`docs/technical-design.md`](docs/technical-design.md) | 版本基线、目录、配置策略和架构边界 |
| [`docs/prototype.md`](docs/prototype.md) | 页面结构、高保真原型和交互状态 |
| [`docs/database-design.md`](docs/database-design.md) | ER 关系、数据约束和迁移策略 |
| [`docs/api.md`](docs/api.md) | REST API 清单、响应和错误约定 |
| [`docs/test-records.md`](docs/test-records.md) | 测试记录：9 项验收结果、自动化清单、真实环境走查、注释比例、未覆盖范围 |
| [`docs/demo-script.md`](docs/demo-script.md) | 演示脚本：完整动线、每步预期结果和可讲的设计理由 |
| [`docs/report-outline.md`](docs/report-outline.md) | 素材索引与答辩问答手册，含 19 条设计决策和关键数字 |
| [`docs/images/screenshots/`](docs/images/screenshots/) | 系统演示截图 27 张，编号即演示顺序 |
| [`软件开发实践2_文字整理.md`](软件开发实践2_文字整理.md) | 课程要求原始整理 |
| [`AGENTS.md`](AGENTS.md) | AI 协作规则和项目现状台账 |
| [`log.md`](log.md) | 工作记录：每次实质变更的内容、验证和下一步 |

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

### AI 辅助出题（可选）

不配置也能正常使用系统，只是「题库管理 → AI 出题」会提示未配置。要启用就在 `.env` 里填四个变量：

| 变量 | 说明 |
|---|---|
| `AI_PROTOCOL` | `openai` 或 `anthropic` |
| `AI_BASE_URL` | `openai` 时带到版本段（如 `https://api.deepseek.com/v1`）；`anthropic` 时只到域名 |
| `AI_MODEL` | 模型名，直接透传给服务商 |
| `AI_API_KEY` | 密钥，只由后端读取，不下发浏览器 |

换服务商（DeepSeek、通义、Kimi、本地 vLLM/Ollama、Anthropic）只改这四个变量，不改代码。AI 只生成草稿，教师确认后仍走普通的题目新增接口保存。

## 演示账号

Flyway V2 与 V4 会创建六个本地演示账号：`admin`、`teacher`、`student`（学生甲）、`student2`（学生乙）、`student3`（学生丙）、`student4`（学生丁），初始密码均为 `ExamDemo123!`。四名学生是为了演示同分并列的 `1、2、2、4` 竞赛排名。这些账号只用于课程演示，非演示环境必须更换或禁用。

可选：使用 `docker compose up -d db` 启动 MySQL。
