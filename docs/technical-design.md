# 技术方案与工程约定

## 1. 版本基线

| 组件 | 固定版本 | 选择依据 |
|---|---:|---|
| Java | 21 | LTS 版本；通过 Maven `release=21` 固定目标字节码 |
| Spring Boot | 3.5.16 | 3.x 稳定维护线，兼顾课程资料和后续 MyBatis-Plus 兼容性 |
| Maven | 3.9.13 | 本机构建版本；项目最低要求 3.9 |
| Node.js | 24 LTS | Node 官方 LTS 线；前端 `engines` 固定最低 24.12 |
| Vue | 3.5.x | Vue 3 稳定线，由 `package-lock.json` 锁定实际安装版本 |
| Vite | 8.x | `create-vue` 当前稳定脚手架生成版本 |
| TypeScript | 6.x | `create-vue` 当前稳定脚手架生成版本 |
| MySQL | 8.4 LTS | MySQL 长期支持线；两位成员均可直接本机安装 |

精确依赖以 `backend/pom.xml` 和 `frontend/package-lock.json` 为准。升级依赖必须先通过后端测试及前端类型检查、Lint、单元测试和生产构建。

## 2. 总体结构

```text
smart-exam-platform/
├── backend/                    Spring Boot REST API
│   ├── src/main/java/          业务源码
│   ├── src/main/resources/     配置与 Flyway 迁移
│   └── src/test/               后端测试
├── frontend/                   Vue 单页应用
│   ├── src/                    页面、路由、状态和接口客户端
│   └── public/                 静态资源
├── docs/                       需求、原型、数据库和 API 文档
├── database/                   本机 MySQL 初始化入口
├── compose.yaml                可选的 MySQL 容器入口
└── .env.example               无敏感信息的配置示例
```

后端后续按业务模块分包，每个模块内部再分 controller、service、repository、model，避免仅按技术层形成跨模块的大目录。建议模块：`auth`、`user`、`question`、`paper`、`exam`、`submission`、`grading`、`common`。

## 3. 运行与配置策略

- 默认使用本机 MySQL 8.4，不要求安装 Docker。首次运行以管理员身份执行 `mysql -uroot -p < database/bootstrap-local.sql` 创建开发库和受限账号。
- `docker compose up -d db` 只是已安装 Docker 时的可选替代路径；容器数据写入命名卷，不提交仓库。
- 后端从环境变量读取数据库地址、账号和密码，开发默认值仅用于本地容器。
- 前端只读取以 `VITE_` 开头的公开构建变量，不得写入数据库密码或 JWT 密钥。
- `.env`、`application-local.*` 和其他本地配置被 Git 忽略；可提交的示例统一写在 `.env.example`。
- 浏览器只访问 `/api`；开发服务器把 `/api` 代理到后端，生产环境由同源反向代理转发。

## 4. 架构边界

```mermaid
flowchart LR
    Browser[Vue SPA] -->|HTTPS / JSON| API[Spring Boot REST API]
    API --> Auth[认证与权限]
    API --> Bank[题库与试卷]
    API --> Exam[考试与答卷]
    API --> Grade[评分与成绩]
    Auth --> DB[(MySQL 8.4)]
    Bank --> DB
    Exam --> DB
    Grade --> DB
```

- Controller 只处理 HTTP 映射、输入校验和响应转换。
- Service 承担权限后的业务规则和事务边界，交卷与计分必须在事务中完成。
- Repository 只负责持久化访问；数据库唯一约束是防重复提交的最终保障。
- API 不直接暴露数据库实体，使用请求/响应 DTO，避免历史字段变化破坏契约。

## 5. 已确定与待确定

已确定：版本基线、目录、配置来源、模块边界、REST/JSON、MySQL/Flyway、Vue SPA。

待后续实现时确定：JWT 库与令牌时效、MyBatis-Plus 精确版本、分页组件字段映射、生产部署域名与 HTTPS 终止方式。未确定项不得写成已实现。
