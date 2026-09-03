# 测试记录

本文件是课程要求的「测试记录」交付物，也是答辩时逐项核对的依据。只记录真实执行过的结果；未执行的项写在第 5 节，不写成通过。

最近一次全量执行：**2026-09-03**。后端 36 项、前端 25 项全部通过。

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
| 统计分析的数据库差异 | 分组计数与聚合只在 H2 上验证过 | 统计 SQL 全部使用 `COUNT(CASE WHEN ...)` 这类标准写法，未用 MySQL 专有函数；但真实 MySQL 上的结果尚未逐项核对 |
| 自动化测试的数据库 | 只跑 H2 手写 schema，不执行 Flyway | H2 与 MySQL 的差异（ENUM、JSON、CHECK）不会被测试发现；改动迁移脚本时必须同步 `schema.sql` |
| 浏览器走查与截图 | 未执行 | 演示材料缺少页面截图；需在最新版 Chrome 完整走查管理员、教师、学生三条动线 |
| AI 真实密钥联调 | 未执行 | 自动化测试使用桩客户端，未验证真实服务商的响应形状；配置错误只会在现场首次调用时暴露 |
| 性能指标 | 未测 | `plan.md` 第 3 节的「1000 题、20 并发、95% 响应 < 1 秒」尚无测量数据，不应在报告中声称达标 |
| 干净环境 10 分钟启动 | 未按 README 从零复现 | 影响「新环境可重复启动」这一完成标准 |
| `scripts/init-local.sh` 的迁移步骤 | 已修复并实测：非 Web 模式启动跑完 Flyway 后正常退出（退出码 0，约 1.3 秒） | 修复前该步骤会永久卡住，见 `log.md` 2026-09-03 记录 |
| 服务端试卷时长约束 | 只按考试 `end_at` 自动交卷 | 试卷时长目前只由前端倒计时约束；学生若绕过前端，可在考试窗口内答满全程 |
| 令牌吊销 | 不维护吊销名单 | 已签发 JWT 在过期前仍有效；停用账号靠每次请求回查用户状态拦截，这是有意取舍 |
