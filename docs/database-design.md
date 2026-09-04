# 数据库设计

## 1. 实体关系

```mermaid
erDiagram
    USER ||--o{ QUESTION : creates
    USER ||--o{ PAPER : creates
    USER ||--o{ EXAM : creates
    USER ||--o{ SUBMISSION : submits
    KNOWLEDGE_POINT ||--o{ QUESTION : classifies
    QUESTION ||--o{ QUESTION_OPTION : has
    PAPER ||--o{ PAPER_QUESTION : contains
    QUESTION ||--o{ PAPER_QUESTION : selected_as_snapshot
    PAPER ||--o{ EXAM : used_by
    EXAM ||--o{ SUBMISSION : receives
    SUBMISSION ||--o{ SUBMISSION_ANSWER : contains
    PAPER_QUESTION ||--o{ SUBMISSION_ANSWER : answered_by
    USER ||--o{ PRACTICE_ATTEMPT : practises
    PAPER_QUESTION ||--o{ PRACTICE_ATTEMPT : practised_as
    USER ||--o{ SYSTEM_SETTING : updates
```

11 张表。其中 `practice_attempt` 与 `system_setting` 是 V5 新增的两张：

- **`practice_attempt`（错题重练记录）** 指向 `paper_question` 而不是 `question`——错题来自某次考试的具体一道题，题库里的原题之后可能被编辑，重练的必须仍是当时那一道快照。每练一次插一行、保留全部历史，因此既能算「练了几次」，也能回答「练到第几次才对」；「已掌握」的判据是 `MAX(id)` 那一行是否 `correct`。这张表**完全独立于答卷**：重练不改动 `submission` 与 `submission_answer` 的任何字段，已公布的成绩不能被学生自己的练习改写。
- **`system_setting`（系统设置覆盖层）** 只保存与默认值不同的项：环境变量仍是默认值，本表是覆盖层，因此「恢复默认」实现为删除该行，而不是写回一个当时的默认值。可覆盖的键由后端白名单固定，数据库连接、JWT 密钥与 AI 密钥一律不进本表。

## 2. 设计约束

- 所有业务主键使用无符号 `BIGINT`，由数据库自增生成。
- 分值使用 `DECIMAL(6,1)`，不使用浮点数。
- 题目加入试卷时在 `paper_question` 保存题干、题型、选项和标准答案快照，确保后来修改题库不改变历史试卷。
- `submission(exam_id, student_id)` 唯一，数据库层阻止重复答卷。
- 用户、题目、试卷等历史关联对象采用状态或软删除，不级联物理删除答卷。
- 时间统一按 UTC 写入数据库，API 使用 ISO 8601；展示时由前端转换为本地时区。`practice_attempt.attempted_at` 与 `system_setting.updated_at` 同样由后端显式写入，不用数据库默认值。
- 阅卷相关字段的语义：`submission_answer.graded_at` 非空即表示该题已评分（主观题判 0 分也算已评），`submission.subjective_score` 与 `total_score` 在每次评分后重算，`exam.results_published_at` 非空即表示成绩已公布。判断「是否已评分」不看分数是否为 0。
- JDBC 连接必须设置 `connectionTimeZone=UTC` 与 `forceConnectionTimeZoneToSession=true`，否则由数据库生成的时间（如 `submission.started_at` 的默认值）会按会话本地时区写入、按 UTC 读出，产生时区偏移。答卷开始时间另由后端显式写入，不依赖数据库默认值。

## 3. 迁移策略

结构迁移位于 `backend/src/main/resources/db/migration/`：V1 创建业务表，V2 创建演示账号，V3 为 `question.type` 增加 `PROGRAMMING` 并新增 `question.tags` 关键词列，V4 补充学生乙/丙/丁三个演示账号以支撑同分并列的排名演示，V5 新增 `system_setting`（可编辑系统设置的覆盖层）与 `practice_attempt`（错题重练记录）两张表。后续变更从 V6 起新增迁移，不修改已执行文件。

测试使用 H2 与 `backend/src/test/resources/schema.sql`，不执行 Flyway；改动迁移脚本时必须同步该文件，并在真实 MySQL 上从空库跑一次迁移。

空数据库验证标准：启动 MySQL 8.4 后运行应用，Flyway 成功创建 11 张业务表和 `flyway_schema_history`，重复启动不重复建表。

当前实测：V1—V5 已在本机 MySQL 9.6 依次应用成功（V5 于 2026-09-04 实测 `Successfully applied 1 migration ... now at version v5`），重复启动提示 `Schema is up to date`。这不等于 8.4 通过（Flyway 11.7 也会提示 9.6 未经测试），MySQL 8.4 空库验证仍是待办项，见 [`test-records.md` 第 5 节](test-records.md#5-未覆盖范围与已知问题)。
