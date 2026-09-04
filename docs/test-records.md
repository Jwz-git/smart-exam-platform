# 测试记录

本文件是课程要求的「测试记录」交付物，也是答辩时逐项核对的依据。只记录真实执行过的结果；未执行的项写在第 5 节，不写成通过。

最近一次全量执行：**2026-09-04**。后端 36 项、前端 25 项全部通过；另完成真实环境浏览器走查与 AI 真实联调，记录见第 6 节。

## 1. 执行环境与命令

| 项目 | 实际值 |
|---|---|
| 操作系统 | macOS（darwin 25.0.0） |
| JDK | OpenJDK 25.0.2（`pom.xml` 里 `java.version` 为 21，向上兼容运行） |
| 构建 | Maven 3.9 + Spring Boot 3.5.16 |
| 测试数据库 | H2 内存库，MySQL 兼容模式，schema 由 `backend/src/test/resources/schema.sql` 建立 |
| 前端 | Node.js 24 LTS、Vue 3.5、Vitest 4、vue-tsc |

复现命令（仓库根目录执行）：

```bash
mvn -f backend/pom.xml test          # 后端 36 项
npm --prefix frontend run type-check # TypeScript 类型检查
npm --prefix frontend run lint       # oxlint + eslint
npm --prefix frontend exec -- vitest run --root frontend # 前端 25 项
npm --prefix frontend run build-only # 生产构建
python3 scripts/comment-ratio.py     # 注释比例核对
```

## 2. 九项核心验收用例

用例正文见 [`plan.md` 第 7.2 节](../plan.md#72-核心验收用例)。每项都由自动化用例覆盖，「关键结果」一列是断言里写死的数字，改动业务逻辑后必须仍然成立。

| # | 验收用例 | 对应自动化用例 | 关键结果 | 结论 |
|---|---|---|---|---|
| 1 | 教师新增不同题型题目，按知识点和难度查询 | `QuestionBankIntegrationTest.createsFourTypesAndFiltersQuestions`、`QuestionTagsAndStatusTest.savesProgrammingQuestionAndNormalizesTags`、`filtersByTagKeywordAndStatus` | 五种题型均可保存；题型+难度+知识点三条件联合筛选命中 1 条 | 通过 |
| 2 | 教师选题生成总分正确的试卷并发布考试 | `ExamFlowIntegrationTest.completesPaperExamSubmissionAndObjectiveScoring`、`rejectsScoreMismatchUnauthorizedAndForeignResources` | 4 题 × 10 分 = 40 分试卷发布成功；分值合计与总分不符返回 `400 PAPER_SCORE_MISMATCH` | 通过 |
| 3 | 学生进入考试、答题并成功交卷 | `ExamFlowIntegrationTest.completesPaperExamSubmissionAndObjectiveScoring` | 开始答卷返回 4 道题；保存答案 200；交卷 200 | 通过 |
| 4 | 客观题得 20 分；重复提交失败且只有一份有效答卷 | 同上 | `objectiveScore = 20.0`；二次交卷 `409 SUBMISSION_ALREADY_SUBMITTED`；`SELECT COUNT(*) FROM submission` 为 1 | 通过 |
| 5 | 教师查看完整答卷、简答评 8 分、公布后学生看到 28 分；公布前不返回分数、答案、评语 | `GradingFlowIntegrationTest.gradesSubjectiveAnswerThenPublishesResultsWithStudentIsolation` | 评分后 `subjectiveScore = 8.0`、`totalScore = 28.0`、答卷状态 `GRADED`；公布前学生响应中 `objectiveScore`、`totalScore`、`rank`、`standardAnswer`、`score`、`gradingComment` 均不存在；公布后学生可读到 28 分与教师评语 | 通过 |
| 6 | 学生只能查看自己的成绩，不能访问他人答卷 | `GradingFlowIntegrationTest.ranksTiedTotalsAsCompetitionRankingAndHidesOtherStudents` | 学生乙访问学生甲答卷 `403`；访问 `/my/results/{他人答卷}` 同样 `403`；访问本人答卷 200 | 通过 |
| 7 | 教师查看排名、平均分、最高分、最低分；同分 `1、2、2、4`；学生只看本人名次 | 同上 | 四份答卷得分 20/10/10/0 → 名次 `1、2、2、4`；`gradedCount = 4`、`averageScore = 10.0`、`highestScore = 20.0`、`lowestScore = 0.0`、`passRate = 25.0`；学生乙只看到「第 2 名 / 共 4 人」 | 通过 |
| 8 | 无权限 403、未登录 401；缺字段和非法状态 400，且含可读错误信息 | `AuthIntegrationTest`（4 项）、`GradingFlowIntegrationTest.rejectsUnauthorizedForbiddenAndPrematurePublish`、`QuestionTagsAndStatusTest.rejectsIllegalPagingWithBadRequest`、`UserAdminIntegrationTest.rejectsDuplicateWeakInvalidAndUnauthorizedRequests` | 未登录 `401 UNAUTHORIZED`；学生访问教师接口 `403 FORBIDDEN`；`page=0` / `size=101` / 非法枚举 `400 VALIDATION_ERROR`；未批完就公布 `400 GRADING_NOT_FINISHED`；仍有人作答 `400 SUBMISSION_IN_PROGRESS` | 通过 |
| 9 | 截止时间到达后自动交卷，学生不能继续修改答案 | `ExamFlowIntegrationTest.automaticallySubmitsExpiredInProgressSubmission` | 触发一次定时扫描后答卷状态变为 `SUBMITTED`；之后再保存答案返回 `409 SUBMISSION_ALREADY_SUBMITTED` | 通过 |

## 3. 自动化用例清单

### 3.1 后端 36 项（MockMvc 集成测试 + 协议单元测试）

| 测试类 | 项数 | 覆盖内容 |
|---|---:|---|
| `system/HealthControllerTest` | 1 | 健康检查 |
| `auth/AuthIntegrationTest` | 4 | 登录、当前用户、401/403、停用账号后旧 JWT 失效 |
| `question/QuestionBankIntegrationTest` | 4 | 知识点归属、四类题型与筛选、编辑删除与非法参数、未登录与学生越权 |
| `question/QuestionTagsAndStatusTest` | 3 | 编程题与标签规范化、标签关键词与状态筛选、非法分页 400 |
| `exam/ExamFlowIntegrationTest` | 4 | 组卷—发布—答题—交卷—客观题判分、分值与越权失败路径、超时自动交卷与交卷后冻结、定时触发器已注册 |
| `exam/GradingFlowIntegrationTest` | 3 | 主观题评分与总分汇总、竞赛排名与隐私隔离、公布前置条件与越权 |
| `user/UserAdminIntegrationTest` | 3 | 用户查询筛选与新增、停用即时生效、重名/弱密码/非法枚举/停用自己 |
| `ai/AiDraftIntegrationTest` | 5 | 草稿生成与确认保存、答案形态归一化、部分不合规的取舍、四类失败降级、角色与参数校验 |
| `ai/RestAiClientTest` | 5 | OpenAI 与 Anthropic 两种协议报文、未配置密钥快速失败、上游错误映射、地址规范化 |
| `stats/StatsIntegrationTest` | 4 | 题库与活动概况（含补 0 分组）、成绩分布与逐题正确率、主观题评分前后的口径差异、越权与 404、系统设置不泄露密钥 |

### 3.2 前端 25 项（Vitest + Vue Test Utils）

| 测试文件 | 项数 | 覆盖内容 |
|---|---:|---|
| `App.spec.ts` | 4 | 登录页初始状态与账号密码不预填、密码可见性切换、分页页码计算与折叠、翻页事件 |
| `grading.spec.ts` | 8 | 答案格式化的三类边界、分数与时间的空值展示、答卷弹窗对不可见字段的处理、评分上界与提交事件 |
| `ai.spec.ts` | 5 | 草稿与丢弃原因渲染、生成参数来自教师选择、采用草稿只抛事件、失败只显示错误、草稿按新增流程提交 |
| `stats.spec.ts` | 8 | 条形图按最大值等比缩放与全 0 兜底、空态文案、概况渲染保留 0 分组、主观题正确率显示破折号、低正确率标红、AI 未配置的降级说明与不渲染密钥、迁移版本与令牌吊销状态如实显示 |

前端另有类型检查、lint 与生产构建三项静态验证，均通过。

## 4. 注释比例核对

课程要求「注释数量多于代码的 1/3」。用 `python3 scripts/comment-ratio.py` 统计（只计整行注释，行尾注释不计入，因此结果偏保守）：

| 分组 | 注释行 | 代码行 | 注释/代码 |
|---|---:|---:|---:|
| 后端主源码 | 1504 | 2620 | 57.4% |
| 后端测试 | 341 | 1197 | 28.5% |
| 前端脚本与组件 | 921 | 3550 | 25.9% |
| 样式 | 82 | 333 | 24.6% |
| **合计** | **2848** | **7700** | **37.0%** |

结论：合计 37.0% > 33.3%，达标。

## 5. 未覆盖范围与已知问题

以下都是尚未执行或尚未实现的项，不计入通过。

| 项 | 现状 | 影响与计划 |
|---|---|---|
| Flyway 在 MySQL 8.4 上从空库执行 | V1—V4 已在本机 **MySQL 9.6** 实测应用成功（`flyway_schema_history` 现为 v4），但不是从空库、也不是 8.4 | 9.6 通过不等于 8.4 通过；Flyway 11.7 也提示「9.6 未经测试」。仍需在 MySQL 8.4 的空库上完整跑一次 V1—V4 |
| 统计分析的数据库差异 | 已在真实 MySQL 9.6 上核对：题型/难度/知识点分布、五段成绩分布与逐题正确率均与界面一致 | 统计 SQL 全部使用 `COUNT(CASE WHEN ...)` 这类标准写法，未用 MySQL 专有函数 |
| 自动化测试的数据库 | 只跑 H2 手写 schema，不执行 Flyway | H2 与 MySQL 的差异（ENUM、JSON、CHECK）不会被测试发现；改动迁移脚本时必须同步 `schema.sql` |
| 浏览器走查与截图 | **已完成**：2026-09-04 在 Google Chrome 152 上完整走查管理员、教师、学生三条动线，留存 27 张截图于 `docs/images/screenshots/` | 见第 6 节 |
| AI 真实密钥联调 | **已完成**：OpenAI 兼容协议 + `deepseek-v4-flash`，生成 2 道草稿、0 道丢弃，1 道确认入库；余额为 0 时的上游 400 被映射为 `AI_REQUEST_FAILED` 并降级 | 见第 6 节 |
| 性能指标 | 未测 | `plan.md` 第 3 节的「1000 题、20 并发、95% 响应 < 1 秒」尚无测量数据，不应在报告中声称达标 |
| 干净环境 10 分钟启动 | 未按 README 从零复现 | 影响「新环境可重复启动」这一完成标准 |
| `scripts/init-local.sh` 的迁移步骤 | 已修复并实测：非 Web 模式启动跑完 Flyway 后正常退出（退出码 0，约 1.3 秒） | 修复前该步骤会永久卡住，见 `log.md` 2026-09-03 记录 |
| 服务端试卷时长约束 | 只按考试 `end_at` 自动交卷 | 试卷时长目前只由前端倒计时约束；学生若绕过前端，可在考试窗口内答满全程 |
| 令牌吊销 | 不维护吊销名单 | 已签发 JWT 在过期前仍有效；停用账号靠每次请求回查用户状态拦截，这是有意取舍 |

## 6. 真实环境走查记录（2026-09-04）

自动化测试跑在 H2 上，因此另外在真实环境完整走查一遍，用来验证「H2 上成立的结论在 MySQL 上同样成立」。

环境：macOS、Google Chrome 152（Playwright 驱动，视口宽 1440—1470 px）、后端 Spring Boot 3.5.16 + 本机 MySQL 9.6（Flyway v4）、前端 Vite 开发服务器。

### 6.1 验收数字复现

| 指标 | 自动化断言 | 真实环境实测 | 一致 |
|---|---|---|:--:|
| 客观题得分 | 20.0 | 20.0（交卷提示「客观题得分 20 分」） | ✅ |
| 主观题得分 | 8.0 | 8.0（教师评 8 分 + 评语） | ✅ |
| 总分 | 28.0 | 28.0 | ✅ |
| 平均 / 最高 / 最低 | 10.0 / 20.0 / 0.0 | 10.0 / 20.0 / 0.0 | ✅ |
| 及格率 | 25.0% | 25% | ✅ |
| 竞赛排名 | `1、2、2、4` | 演示学生 1、学生乙 2、学生丙 2、学生丁 4 | ✅ |
| 成绩分布 | 28/40 = 70% 落在 70—79% | 该段 1 份 100%，其余四段显示 0 | ✅ |
| 逐题正确率 | 单选 100%、多选 0%、简答 `—` | 一致，且多选 0% 标红、简答得分率 80% | ✅ |

### 6.2 权限与异常路径（`curl` 实测）

| 场景 | 实测 |
|---|---|
| 学生甲访问本人答卷 | `200` |
| 学生乙访问学生甲答卷 | `403` |
| 学生乙访问 `/my/results/{他人答卷}` | `403` |
| 未登录访问题库 | `401` |
| 学生访问教师题库接口 | `403` |
| 教师 `page=0` / `size=101` / 非法枚举 | `400 VALIDATION_ERROR` ×3 |
| 重复交卷 | `409 SUBMISSION_ALREADY_SUBMITTED` |
| 重复公布成绩 | `409 INVALID_STATE_TRANSITION` |
| 该考试答卷份数 | `1`（唯一约束生效） |

### 6.3 AI 辅助出题真实联调

| 项 | 结果 |
|---|---|
| 协议 / 模型 | OpenAI 兼容 / `deepseek-v4-flash` |
| 生成结果 | 2 道草稿全部通过校验，0 道被丢弃 |
| 入库 | 1 道经教师在「确认 AI 草稿」表单修改后保存，走普通 `POST /questions` |
| 上游失败降级 | 账号余额为 0 时上游返回 400，被映射为 `AI_REQUEST_FAILED`「AI 服务返回 400，请检查密钥、模型名与配额」，手工出题不受影响 |
| 未配置降级 | 未设 `AI_API_KEY` 时提示「未配置 AI 密钥，无法生成草稿；手工出题不受影响」 |
| 密钥不泄露 | `GET /system/settings` 响应体中检索 `sk-` 结果为 `False`，只返回 `configured: true` |

### 6.4 走查中发现并修复的界面缺陷

自动化测试全绿的情况下，浏览器走查仍发现 6 个问题，均已修复：

| # | 问题 | 修复 |
|---|---|---|
| 1 | 管理员登录后首页请求教师接口，控制台出现 4 条 403 并显示无意义错误 | `DashboardView` 按角色取数：管理员看账号构成与最近账号 |
| 2 | `RESULTS_PUBLISHED` 状态被「等于 PUBLISHED 否则草稿」的两分法显示成「草稿」 | 改用 `examStatusLabels`；已发布计数改为「非草稿」 |
| 3 | `.btn-link` 无 `:disabled` 样式，「不能停用当前登录账号」的守卫在界面上看不出来 | 补 `:disabled` 灰化与 `not-allowed` 光标 |
| 4 | `.btn-link` 作为 flex 项被压缩，选项编辑器里「删除」断成两行 | 补 `white-space: nowrap; flex: none` |
| 5 | 每次加载控制台首条红字是 `/favicon.ico` 404 | `index.html` 内联 SVG 图标 |
| 6 | 换人登录后视图不重置，教师停在题库管理退出、管理员登入仍渲染题库页 | `watch(user.id)` 时退回首页 |

修复后重新执行全部验证：后端 36 项通过（`BUILD SUCCESS`）、前端 25 项通过、类型检查与 Lint 通过、生产构建成功（163 KB / gzip 53.8 KB）、注释比例 37.1%。

### 6.5 截图清单

27 张，位于 `docs/images/screenshots/`，编号即演示顺序：

`01-login` `02-admin-dashboard` `03-admin-users` `04-teacher-dashboard` `05-question-bank` `06-question-form` `07-ai-draft` `08-ai-draft-confirm` `09-ai-draft-saved` `10-ai-not-configured` `11-paper-compose` `12-exam-manage` `13-student-exam-list` `14-student-answering` `15-student-short-answer` `16-answer-restored` `17-submit-result` `18-grading-board` `19-grading-modal` `20-grading-scored` `21-results-published` `22-ranking-tie` `23-stats-overview` `24-system-settings` `25-token-expired` `26-student-my-results` `27-student-review`
