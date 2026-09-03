# REST API 契约（MVP 首版）

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

## 2. 接口清单

| 模块 | 方法与路径 | 角色 | 用途 |
|---|---|---|---|
| 系统 | `GET /api/health` | 公开 | 骨架健康检查 |
| 认证 | `POST /api/v1/auth/login` | 公开 | 登录并取得令牌 |
| 认证 | `GET /api/v1/auth/me` | 已登录 | 当前用户信息 |
| 认证 | `POST /api/v1/auth/logout` | 已登录 | 无状态退出，客户端删除令牌 |
| 用户 | `GET /api/v1/users` | 管理员 | 分页查询用户 |
| 用户 | `POST /api/v1/users` | 管理员 | 新增用户 |
| 用户 | `PATCH /api/v1/users/{id}/status` | 管理员 | 启用或禁用 |
| 知识点 | `GET /api/v1/knowledge-points` | 教师 | 查询知识点 |
| 知识点 | `POST /api/v1/knowledge-points` | 教师 | 新增知识点 |
| 知识点 | `PUT /api/v1/knowledge-points/{id}` | 创建者教师 | 编辑知识点 |
| 知识点 | `DELETE /api/v1/knowledge-points/{id}` | 创建者教师 | 未被题目引用时删除 |
| 题目 | `GET /api/v1/questions` | 教师 | 按关键词、题型、难度、知识点筛选 |
| 题目 | `POST /api/v1/questions` | 教师 | 新增题目 |
| 题目 | `GET /api/v1/questions/{id}` | 教师 | 题目详情 |
| 题目 | `PUT /api/v1/questions/{id}` | 教师 | 编辑题目 |
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
| 答卷 | `PUT /api/v1/submissions/{id}/answers` | 答卷本人 | 保存当前答案 |
| 答卷 | `POST /api/v1/submissions/{id}/submit` | 答卷本人 | 事务交卷并判客观题 |
| 阅卷 | `GET /api/v1/exams/{id}/grading` | 考试教师 | 待阅卷列表 |
| 阅卷 | `PUT /api/v1/submission-answers/{id}/score` | 考试教师 | 简答题评分与评语 |
| 成绩 | `POST /api/v1/exams/{id}/publish-results` | 考试教师 | 全部批完后公布成绩 |
| 成绩 | `GET /api/v1/exams/{id}/results` | 考试教师 | 成绩列表与基础统计 |
| 成绩 | `GET /api/v1/my/results` | 学生 | 本人已公布成绩 |
| 成绩 | `GET /api/v1/my/results/{submissionId}` | 答卷本人 | 本人成绩详情 |

## 3. 关键幂等与冲突规则

- JWT 访问令牌默认有效期为 60 分钟。退出接口返回 `204`，客户端必须删除令牌；当前 MVP 不维护令牌撤销名单，已签发令牌在过期前仍具备密码学有效性。

- 同一学生重复开始同一考试时返回现有未提交答卷；若已提交则返回 `409 SUBMISSION_ALREADY_SUBMITTED`。
- 重复交卷不再次计分，返回 `409 SUBMISSION_ALREADY_SUBMITTED`。
- 后端每 30 秒扫描一次已到截止时间且仍在答题的答卷，并在事务内自动交卷、判定客观题；该机制不依赖浏览器保持打开。
- 发布试卷、考试或成绩时状态不满足，返回 `409 INVALID_STATE_TRANSITION`。
- 前端不得依赖按钮隐藏实现权限；所有角色、资源归属和业务状态由后端再次校验。

## 4. 试卷、考试与答卷请求约定

- 创建试卷必须提交 `name`、`durationMinutes`、`totalScore` 和非空 `questions`；每项题目包含 `questionId`、`score`，分值合计必须严格等于总分。
- 创建考试必须引用本人已发布试卷，并提交 `name`、`paperId`、`startAt`、`endAt`；结束时间必须晚于开始时间且仍在未来。
- 保存答案使用 `{"answers":[{"paperQuestionId":1,"answerContent":["A"]}]}`；判断题答案为布尔值，简答题答案为字符串。
- 试卷题目在创建时保存题干、选项、答案、解析和题型快照，之后修改题库不会改变历史试卷。

## 5. 题目答案约定

- 单选题和多选题必须至少包含两个唯一选项，标准答案为选项键 JSON 数组；单选题数组长度必须为 1。
- 判断题不包含选项，标准答案为 JSON 布尔值。
- 简答题不包含选项，标准答案为非空 JSON 字符串。
- 教师只能查询、查看、修改和删除自己创建的题目；删除已被试卷引用的题目时改为停用，保留历史数据。
