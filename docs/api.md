# REST API 契约

本表同时包含已实现接口和已确认待实现接口；真实完成状态以 [`AGENTS.md`](../AGENTS.md) 为准。

## 1. 通用约定

- 基础路径：`/api/v1`；健康检查：`/api/health`。
- JSON 字段使用 `camelCase`；时间使用带时区的 ISO 8601 字符串；分值最多一位小数。
- 认证成功后使用 `Authorization: Bearer <token>`；登录接口除外。
- 分页参数为 `page`（从 1 开始）、`size`（默认 20，最大 100）；返回 `items`、`page`、`size`、`total`。

成功响应：

```json
{"data": {}, "requestId": "01J..."}
```

错误响应：

```json
{"code": "VALIDATION_ERROR", "message": "请求参数不合法", "fieldErrors": {"name": "不能为空"}, "requestId": "01J..."}
```

状态码：参数或非法业务状态 `400`，未登录 `401`，越权 `403`，资源不存在 `404`，唯一约束或状态冲突 `409`，未处理异常 `500`。

参数校验失败一律返回 `400 VALIDATION_ERROR`，包括请求体字段校验、查询参数范围（如 `page`、`size`）和无法转换的取值（如非法枚举）。未预期的异常返回 `500 INTERNAL_ERROR`，响应体使用同一结构，不包含堆栈信息。

## 2. 接口清单

| 模块 | 方法与路径 | 角色 | 用途 |
|---|---|---|---|
| 系统 | `GET /api/health` | 公开 | 健康检查 |
| 认证 | `POST /api/v1/auth/login` | 公开 | 登录并取得令牌 |
| 认证 | `GET /api/v1/auth/me` | 已登录 | 当前用户信息 |
| 认证 | `POST /api/v1/auth/logout` | 已登录 | 无状态退出，客户端删除令牌 |
| 用户 | `GET /api/v1/users` | 管理员 | 按关键词、角色、状态分页查询用户 |
| 用户 | `POST /api/v1/users` | 管理员 | 新增用户 |
| 用户 | `PATCH /api/v1/users/{id}/status` | 管理员 | 启用或禁用 |
| 知识点 | `GET /api/v1/knowledge-points` | 教师 | 查询知识点 |
| 知识点 | `POST /api/v1/knowledge-points` | 教师 | 新增知识点 |
| 知识点 | `PUT /api/v1/knowledge-points/{id}` | 创建者教师 | 编辑知识点 |
| 知识点 | `DELETE /api/v1/knowledge-points/{id}` | 创建者教师 | 未被题目引用时删除 |
| 题目 | `GET /api/v1/questions` | 教师 | 按关键词、题型、难度、知识点、状态筛选 |
| 题目 | `POST /api/v1/questions` | 教师 | 新增题目 |
| 题目 | `GET /api/v1/questions/{id}` | 教师 | 题目详情 |
| 题目 | `PUT /api/v1/questions/{id}` | 教师 | 编辑题目 |
| 题目 | `PATCH /api/v1/questions/{id}/status` | 创建者教师 | 启用或停用题目 |
| 题目 | `DELETE /api/v1/questions/{id}` | 教师 | 未引用时删除，否则停用 |
| 试卷 | `GET /api/v1/papers` | 教师 | 查询本人试卷 |
| 试卷 | `POST /api/v1/papers` | 教师 | 创建草稿并选题 |
| 试卷 | `GET /api/v1/papers/{id}` | 教师 | 预览试卷 |
| 试卷 | `POST /api/v1/papers/{id}/publish` | 教师 | 校验并发布试卷 |
| 考试 | `GET /api/v1/exams` | 教师/学生 | 按角色查询考试 |
| 考试 | `POST /api/v1/exams` | 教师 | 基于已发布试卷创建考试 |
| 考试 | `POST /api/v1/exams/{id}/publish` | 教师 | 发布考试 |
| 考试 | `GET /api/v1/exams/{id}` | 教师/可参加学生 | 查看考试信息 |
| 答卷 | `POST /api/v1/exams/{id}/submissions` | 学生 | 开始答卷 |
| 答卷 | `GET /api/v1/submissions/{id}` | 答卷本人/考试教师 | 查看答卷；按角色和公布状态裁剪敏感字段 |
| 答卷 | `PUT /api/v1/submissions/{id}/answers` | 答卷本人 | 保存当前答案 |
| 答卷 | `POST /api/v1/submissions/{id}/submit` | 答卷本人 | 事务交卷并判客观题 |
| 阅卷 | `GET /api/v1/exams/{id}/grading` | 考试教师 | 待阅卷列表 |
| 阅卷 | `PUT /api/v1/submission-answers/{id}/score` | 考试教师 | 简答题评分与评语 |
| 成绩 | `POST /api/v1/exams/{id}/publish-results` | 考试教师 | 全部批完后公布成绩 |
| 成绩 | `GET /api/v1/exams/{id}/results` | 考试教师 | 排名、平均分、最高分和最低分 |
| 成绩 | `GET /api/v1/my/results` | 学生 | 本人已公布成绩 |
| 成绩 | `GET /api/v1/my/results/{submissionId}` | 答卷本人 | 本人成绩详情 |
| AI 出题 | `POST /api/v1/ai/question-drafts` | 教师 | 调用配置的大模型生成题目草稿，不直接入库 |
| 统计 | `GET /api/v1/stats/overview` | 教师 | 本人题库分布与教学活动概况 |
| 统计 | `GET /api/v1/stats/exams/{id}` | 考试教师 | 单场考试的成绩分布与逐题正确率 |
| 系统 | `GET /api/v1/system/settings` | 教师/管理员 | 当前生效的运行参数，不含任何密钥 |

## 3. 关键幂等与冲突规则

- JWT 访问令牌默认有效期为 60 分钟。退出接口返回 `204`，客户端必须删除令牌；当前 MVP 不维护令牌撤销名单，已签发令牌在过期前仍具备密码学有效性。

- 同一学生重复开始同一考试时返回现有未提交答卷；若已提交则返回 `409 SUBMISSION_ALREADY_SUBMITTED`。
- 重复交卷不再次计分，返回 `409 SUBMISSION_ALREADY_SUBMITTED`。
- 后端每 30 秒扫描一次已到截止时间且仍在答题的答卷，并在事务内自动交卷、判定客观题；该机制不依赖浏览器保持打开。
- 发布试卷、考试或成绩时状态不满足，返回 `409 INVALID_STATE_TRANSITION`。
- 前端不得依赖按钮隐藏实现权限；所有角色、资源归属和业务状态由后端再次校验。
- 成绩公布前，学生响应不得包含得分、标准答案、解析、评语或排名；公布后仍只能读取本人答卷。
- 排名只统计已评分的有效答卷，按总分降序采用 `1、2、2、4` 的竞赛排名。
- 公布成绩会同时关闭考试：状态变为 `RESULTS_PUBLISHED` 后学生无法再开始或继续作答，教师也不能再修改评分。该操作没有撤回接口。
- 学生的考试列表只返回 `PUBLISHED` 的考试，因此已公布成绩的考试不再出现在考试列表里，学生改从 `GET /api/v1/my/results` 查看。

## 4. 试卷、考试与答卷请求约定

- 创建试卷必须提交 `name`、`durationMinutes`、`totalScore` 和非空 `questions`；每项题目包含 `questionId`、`score`，分值合计必须严格等于总分。
- 创建考试必须引用本人已发布试卷，并提交 `name`、`paperId`、`startAt`、`endAt`；结束时间必须晚于开始时间且仍在未来。
- 保存答案使用 `{"answers":[{"paperQuestionId":1,"answerContent":["A"]}]}`；判断题答案为布尔值，简答题答案为字符串。
- 开始答卷接口写入的 `startedAt` 由后端生成并以 UTC 返回；前端答题倒计时取“考试结束时间”与“开始作答 + 试卷时长”中更早的一个。
- 开始答卷与查询答卷都会返回 `savedAnswers`（`paperQuestionId` 与 `answerContent` 两个字段），前端据此恢复答题界面。服务端这一份才是超时自动交卷实际判分的依据；浏览器本地草稿只用于补服务端没有收到的题目，不允许覆盖服务端已有答案。
- 试卷题目在创建时保存题干、选项、答案、解析和题型快照，之后修改题库不会改变历史试卷。

## 5. 题目答案约定

- 支持五种题型：单选题、多选题、判断题、简答题、编程题。
- 单选题和多选题必须至少包含两个唯一选项，标准答案为选项键 JSON 数组；单选题数组长度必须为 1。
- 判断题不包含选项，标准答案为 JSON 布尔值。
- 简答题和编程题不包含选项，标准答案为非空 JSON 字符串，只作为教师阅卷时的参考答案；两者都不参与客观题自动判分。
- `tags` 为可选的关键词标签，最长 200 字符，保存时按英文逗号规范化为 `a, b`；只用于列表展示和 `keyword` 检索，不参与判分。
- `keyword` 同时匹配题干和标签，`%` 与 `_` 按普通字符处理。
- `status` 取 `ACTIVE` 或 `DISABLED`；不传时返回全部状态，界面据此显示启用/停用并提供切换。
- 停用题目不能加入新试卷，但已引用它的历史试卷快照不受影响。
- 教师只能查询、查看、修改和删除自己创建的题目；删除已被试卷引用的题目时改为停用，保留历史数据。

## 6. 阅卷与成绩约定

- 只有简答题和编程题可以人工评分；对客观题调用评分接口返回 `400 NOT_SUBJECTIVE`，客观题分数只能由系统判定。
- 单题得分必须满足 `0 ≤ score ≤ 该题在本试卷中的分值`，超出返回 `400 SCORE_EXCEEDS_MAX`；最多一位小数。
- 评语可空，最长 1000 字符；空白评语按 `null` 存储。
- 「是否已评分」以 `gradedAt` 是否为空判断，不以分数是否为 0 判断——主观题被判 0 分也是已评分。
- 每次评分后立即重算该答卷的 `subjectiveScore` 与 `totalScore`（`totalScore = objectiveScore + subjectiveScore`）；该答卷的主观题全部评完时状态推进为 `GRADED`。
- 评分接口返回重算后的完整答卷详情，前端不需要再查一次。
- 未交卷的答卷不能评分，返回 `400 SUBMISSION_NOT_SUBMITTED`。
- 公布成绩的前置条件有三条，任一不满足返回 400：至少一份答卷（`NO_SUBMISSION`）、无人仍在作答（`SUBMISSION_IN_PROGRESS`）、全场主观题已批完（`GRADING_NOT_FINISHED`）。重复公布返回 `409 INVALID_STATE_TRANSITION`。
- 统计口径：`gradedCount`、`averageScore`、`highestScore`、`lowestScore`、`passRate` 只统计已评完的答卷；平均分与及格率保留一位小数；及格线为试卷总分的 60%。没有有效答卷时这些字段为 `null`，不返回 0。
- 排名入选条件是「已交卷」且「没有未评分的主观题」，因此全客观题的答卷交卷后即进入排名，不需要教师操作。
- 学生成绩接口只返回本人 `rank` 与参与人数 `totalCount`，不返回其他学生的姓名或分数。

## 7. 用户管理约定

- 用户名 3–64 位，只允许字母、数字、下划线、点和减号；重复返回 `409 USERNAME_EXISTS`。
- 初始密码至少 8 位，由后端 BCrypt 哈希后存储；任何响应都不含密码或哈希字段。演示环境未强制复杂度，真实部署应当收紧。
- 只提供查询、新增、启用/停用；不提供删除、改密码和改角色。存在答卷的账号删除后成绩会失去归属，因此一律只停用。
- 不允许停用当前登录的管理员本人，返回 `400 CANNOT_DISABLE_SELF`。
- 停用立即生效：账号无法登录，此前签发的 JWT 也会在下一次请求时被拒绝（每次鉴权都回查用户状态）。

## 8. AI 草稿约定

- 请求字段：`knowledgePointId`、`type`、`difficulty` 必填，`count`（1–5，默认 1）、`suggestedScore`（默认 10）、`requirement`（≤500 字）可选。题型、难度、知识点和分值由教师指定而不是模型决定，模型只负责题干、选项、答案和解析。
- 响应为 `{protocol, model, drafts, warnings}`；`drafts` 的元素与题目新增请求体完全同构，教师确认后原样提交即可。
- 教师编辑并确认后，仍通过题目新增接口和现有业务校验保存；本接口不写库。
- 模型输出先做形态归一化（选项键转大写、`"AB"` 拆成 `["A","B"]`、`"正确"` 转 `true`），再走与手工出题完全相同的校验。不合规的草稿被丢弃并在 `warnings` 里说明原因，全部不合规时返回 `502 AI_DRAFT_INVALID`。
- 错误码：未配置密钥 `503 AI_NOT_CONFIGURED`；上游报错或超时 `502 AI_REQUEST_FAILED`；空内容 `502 AI_EMPTY_RESPONSE`；输出不是合法 JSON 或缺少 `questions` 数组 `502 AI_INVALID_JSON`。以上都不影响手工出题与考试主流程。
- 服务商可自定义：`AI_PROTOCOL`（`openai` 或 `anthropic`）、`AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 均为后端配置，密钥不下发浏览器。
- 协议差异只体现在适配层：OpenAI 兼容服务走 `POST {baseUrl}/chat/completions` 并读 `choices[0].message.content`；Anthropic 走 `POST {baseUrl}/v1/messages`、附 `anthropic-version` 头并读 `content[0].text`。
- 未配置密钥、超时、限流、空内容或非法 JSON 返回可读错误，不影响手工出题和考试主流程。

## 9. 统计分析约定

- 范围一律取自令牌里的当前教师，路径和查询参数都不接受「看谁的数据」：题库计数与题库列表使用同一个 `created_by` 条件，因此统计数字与题库列表永远一致。
- 题型和难度分布由服务端补齐计数为 0 的分组并固定顺序（题型五项、难度三项）。数据库 `GROUP BY` 只返回出现过的值，补 0 才能让教师看出「一道编程题都没有」这种覆盖缺口。
- 单场考试的 `averageScore`、`highestScore`、`lowestScore`、`passRate`、`gradedCount` 直接复用 `GET /api/v1/exams/{id}/results` 的实现，成绩管理页与统计分析页不存在两套数字；归属校验同样复用，不是自己的考试返回 `403 RESOURCE_FORBIDDEN`，考试不存在返回 `404 EXAM_NOT_FOUND`。
- `distribution` 固定返回五段，按占试卷满分的百分比划分：`0—59%（不及格）`、`60—69%`、`70—79%`、`80—89%`、`90—100%`。`ratio` 的分母是已评完的答卷数。
- `questions` 是逐题统计，口径按题型区分：
  - 客观题统计全部已交卷答卷（交卷事务里已判完分），`fullMarkCount` 即答对人数（多选不给部分分），`correctRate` = 满分人数 / 答卷数；
  - 主观题只统计已评分的答卷，`correctRate` 与 `fullMarkCount` 为 `null`（主观题没有对错），未评分的数量单独放在 `ungradedCount`；一份都没批时 `averageScore` 为 `null` 而不是 0。
- `blankCount` 是留空人数。SQL NULL、JSON 的 `null`、空字符串和空数组四种形态都算留空，它们来自不同的写入路径（保存答案写 NULL、自动判分补行写 `'null'`、学生清空输入或取消勾选写 `""`/`[]`）。

## 10. 系统设置约定

- 只读接口，没有对应的写接口。所有可配置项都来自后端环境变量，界面提供编辑入口只会造成「显示值与进程生效值不一致」。
- 响应分五组：`runtime`（服务名、Spring Boot 与 Java 版本、服务端时区与当前时间）、`security`（令牌类型与有效期、密码哈希算法、是否维护吊销名单）、`exam`（自动交卷扫描间隔、及格线比例、排名规则、部分分规则）、`ai`、`database`。
- **不包含任何密钥、密码或数据库连接串。** AI 密钥只以 `ai.configured` 这个布尔值体现「配了还是没配」，其余字段仅为协议、地址、模型和超时等非机密配置。
- `database.schemaVersion` 是已成功应用的最高 Flyway 版本号；读不到迁移历史表时为 `null`（例如自动化测试用的 H2 手写 schema），不编造版本号。
- 角色限定为教师或管理员：匿名可访问的 `GET /api/health` 只回状态和时间戳，不返回版本与环境信息，避免给未登录者做信息收集。
