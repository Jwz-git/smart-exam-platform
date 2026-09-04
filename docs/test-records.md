# 测试记录

本文件是课程要求的「测试记录」交付物，也是答辩时逐项核对的依据。只记录真实执行过的结果；未执行的项写在第 5 节，不写成通过。

最近一次全量执行：**2026-09-04**。后端 55 项、前端 55 项全部通过。真实环境走查分三批完成（主流程与 AI 联调、题库批量导入、四项新功能），记录见第 6—8 节；三批共发现并修复 11 个界面缺陷，汇总在 [6.4](#64-走查中发现并修复的-11-个界面缺陷)。

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
mvn -f backend/pom.xml test          # 后端 55 项
npm --prefix frontend run type-check # TypeScript 类型检查
npm --prefix frontend run lint       # oxlint + eslint
npm --prefix frontend exec -- vitest run --root frontend # 前端 55 项
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

### 3.1 后端 55 项（MockMvc 集成测试 + 协议单元测试）

| 测试类 | 项数 | 覆盖内容 |
|---|---:|---|
| `system/HealthControllerTest` | 1 | 健康检查 |
| `auth/AuthIntegrationTest` | 4 | 登录、当前用户、401/403、停用账号后旧 JWT 失效 |
| `question/QuestionBankIntegrationTest` | 4 | 知识点归属、四类题型与筛选、编辑删除与非法参数、未登录与学生越权 |
| `question/QuestionTagsAndStatusTest` | 3 | 编程题与标签规范化、标签关键词与状态筛选、非法分页 400 |
| `question/QuestionImportIntegrationTest` | 6 | 预览与导入行数一致且预览不写库、模板自导入、逐行失败带行号、文件内与库内判重、制表符/BOM/引号换行、角色与整表级校验 |
| `exam/ExamFlowIntegrationTest` | 4 | 组卷—发布—答题—交卷—客观题判分、分值与越权失败路径、超时自动交卷与交卷后冻结、定时触发器已注册 |
| `exam/PaperAutoComposeIntegrationTest` | 3 | 按规则抽题且方案能原样保存成试卷、题目不够/规则互相抢题/超上限三条失败路径、只抽本人启用题目与角色校验 |
| `exam/PaperExportIntegrationTest` | 3 | 不含答案的 Markdown 里确实没有答案、含答案版附参考答案区、导出的 CSV 能被导入接口原样读回、归属与格式校验 |
| `exam/GradingFlowIntegrationTest` | 3 | 主观题评分与总分汇总、竞赛排名与隐私隔离、公布前置条件与越权 |
| `user/UserAdminIntegrationTest` | 3 | 用户查询筛选与新增、停用即时生效、重名/弱密码/非法枚举/停用自己 |
| `ai/AiDraftIntegrationTest` | 5 | 草稿生成与确认保存、答案形态归一化、部分不合规的取舍、四类失败降级、角色与参数校验 |
| `ai/RestAiClientTest` | 5 | OpenAI 与 Anthropic 两种协议报文、未配置密钥快速失败、上游错误映射、地址规范化 |
| `practice/WrongQuestionPracticeIntegrationTest` | 3 | 公布前错题本为空、公布后收录未得满分的题、练习集不含答案、练对后标记已掌握且成绩分毫未动、越权与参数校验、未作答同样进错题本 |
| `system/SettingsIntegrationTest` | 4 | 可编辑项的当前值/默认值/来源、改及格线后成绩与统计的及格率立即重算、改导入上限后导入立即受限、越界与未知键拒绝且整批不生效、写操作限管理员 |
| `stats/StatsIntegrationTest` | 4 | 题库与活动概况（含补 0 分组）、成绩分布与逐题正确率、主观题评分前后的口径差异、越权与 404、系统设置不泄露密钥 |

### 3.2 前端 55 项（Vitest + Vue Test Utils）

| 测试文件 | 项数 | 覆盖内容 |
|---|---:|---|
| `App.spec.ts` | 4 | 登录页初始状态与账号密码不预填、密码可见性切换、分页页码计算与折叠、翻页事件 |
| `grading.spec.ts` | 8 | 答案格式化的三类边界、分数与时间的空值展示、答卷弹窗对不可见字段的处理、评分上界与提交事件 |
| `ai.spec.ts` | 5 | 草稿与丢弃原因渲染、生成参数来自教师选择、采用草稿只抛事件、失败只显示错误、草稿按新增流程提交 |
| `import.spec.ts` | 6 | 预览传 dryRun 并逐行渲染、预览前禁用导入按钮、改内容作废预览、选文件填入同一输入框、模板走后端接口、整表失败不渲染结果表 |
| `stats.spec.ts` | 8 | 条形图按最大值等比缩放与全 0 兜底、空态文案、概况渲染保留 0 分组、主观题正确率显示破折号、低正确率标红、AI 未配置的降级说明与不渲染密钥、迁移版本与令牌吊销状态如实显示 |
| `autoCompose.spec.ts` | 6 | 「不限」提交为 null 而不是空串、按条件提交规则、增删规则且至少留一条、方案渲染与「无可换」提示、应用方案只抛事件、抽题失败只显示错误 |
| `export.spec.ts` | 5 | 默认取不含答案的 Markdown、勾选含答案后重新取、切到 CSV 后锁定含答案开关并说明原因、下载复用已取回的内容不再请求、取内容失败时禁用下载 |
| `practice.spec.ts` | 7 | 区分可重练客观题与仅复习主观题、未作答与答错分开显示、展开后才给参考答案与评语、无客观错题时禁用重练、重练界面不显示答案而提交后才给、未作答提交 null、无题可练时只显示错误 |
| `settings.spec.ts` | 6 | 管理员可编辑而教师只读、只提交改动过的键、恢复默认提交空串、放弃改动不发请求、保存失败保留原值、未改动时禁用保存 |

前端另有类型检查、lint 与生产构建三项静态验证，均通过。

## 4. 注释比例核对

课程要求「注释数量多于代码的 1/3」。用 `python3 scripts/comment-ratio.py` 统计（只计整行注释，行尾注释不计入，因此结果偏保守）：

| 分组 | 注释行 | 代码行 | 注释/代码 |
|---|---:|---:|---:|
| 后端主源码 | 2394 | 3969 | 60.3% |
| 后端测试 | 597 | 2171 | 27.5% |
| 前端脚本与组件 | 1370 | 5305 | 25.8% |
| 样式 | 92 | 338 | 27.2% |
| **合计** | **4453** | **11783** | **37.8%** |

结论：合计 37.8% > 33.3%，达标。

## 5. 未覆盖范围与已知问题

只列**尚未执行或尚未实现**的项，一律不计入通过。已经完成的真实环境验证（浏览器走查、AI 联调、批量导入、四项新功能）记在第 6—8 节，不在本表重复。

| 项 | 现状 | 影响与计划 |
|---|---|---|
| Flyway 在 MySQL 8.4 上从空库执行 | V1—V5 已在本机 **MySQL 9.6** 实测应用成功（2026-09-04 应用 V5：`Successfully applied 1 migration ... now at version v5`），但不是从空库、也不是 8.4 | 9.6 通过不等于 8.4 通过；Flyway 11.7 也提示「9.6 未经测试」。仍需在 MySQL 8.4 的空库上完整跑一次 V1—V5 |
| 干净环境 10 分钟启动 | 未按 README 从零复现 | 影响「新环境可重复启动」这一完成标准 |
| 性能指标 | 未测 | `plan.md` 第 3 节的「1000 题、20 并发、95% 响应 < 1 秒」尚无测量数据，不应在报告中声称达标 |
| 自动化测试的数据库 | 只跑 H2 手写 schema，不执行 Flyway | H2 与 MySQL 的差异（ENUM、JSON、CHECK）不会被测试发现；改动迁移脚本时必须同步 `schema.sql` |
| 服务端试卷时长约束 | 只按考试 `end_at` 自动交卷 | 试卷时长目前只由前端倒计时约束；学生若绕过前端，可在考试窗口内答满全程 |
| 令牌吊销 | 不维护吊销名单 | 已签发 JWT 在过期前仍有效；停用账号靠每次请求回查用户状态拦截，这是有意取舍 |
| 自动组卷的随机性 | 抽题用 `Collections.shuffle`（`SecureRandom`），不使用 `ORDER BY RAND()` | 候选池较大时同一批规则每次给出不同组合，这是刻意的（「换一批」）；测试断言的是数量、去重与筛选条件，不断言具体抽中哪几道 |
| 错题本与多场考试复用同一试卷 | 同一份试卷被两场考试引用、学生在两场里都错过时，同一道题可能出现两行 | 练习记录按试卷题目聚合，两行共享练习次数与「已掌握」标记。课程演示规模下不影响使用，未做去重 |
| 系统设置的多实例部署 | 覆盖值缓存在进程内存里，写入后立即失效重载 | 单实例部署下正确；若将来多实例，需要改成带过期时间的缓存或写入后广播 |
| 本机开发库的 Flyway 历史 | **已修复**：校验开启也能启动（`Successfully validated 5 migrations`、`Current version: 5`） | 原因是 V1 被补注释后校验和失效，加上 `target/classes` 里残留了已删除的旧 V5。修法与备份见 [`log.md`](../log.md) 2026-09-04 记录。**空库不受此影响** |

## 6. 真实环境走查记录（2026-09-04）

自动化测试跑在 H2 上，因此另外在真实环境完整走查一遍，用来验证「H2 上成立的结论在 MySQL 上同样成立」。第 7、8 节是随后两批新功能的同类记录，环境相同，只有数据库迁移版本随功能推进（v4 → v5）。

环境：macOS、Google Chrome 152（Playwright 驱动，视口宽 1440—1470 px）、后端 Spring Boot 3.5.16 + 本机 MySQL 9.6、前端 Vite 开发服务器。

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

### 6.4 走查中发现并修复的 11 个界面缺陷

三批走查（主流程、批量导入、四项新功能）在自动化测试全绿的前提下共发现 11 个界面缺陷，均已修复。它们全都不是后端逻辑错误，却全都会出现在验收现场。

| # | 批次 | 问题 | 修复 |
|---:|---|---|---|
| 1 | 主流程 | 管理员登录后首页请求教师接口，控制台出现 4 条 403 并显示无意义错误 | `DashboardView` 按角色取数：管理员看账号构成与最近账号 |
| 2 | 主流程 | `RESULTS_PUBLISHED` 被「等于 PUBLISHED 否则草稿」的两分法显示成「草稿」 | 改用 `examStatusLabels`；已发布计数改为「非草稿」 |
| 3 | 主流程 | `.btn-link` 无 `:disabled` 样式，「不能停用当前登录账号」的守卫在界面上看不出来 | 补 `:disabled` 灰化与 `not-allowed` 光标 |
| 4 | 主流程 | `.btn-link` 作为 flex 项被压缩，选项编辑器里「删除」断成两行 | 补 `white-space: nowrap; flex: none` |
| 5 | 主流程 | 每次加载控制台首条红字是 `/favicon.ico` 404 | `index.html` 内联 SVG 图标 |
| 6 | 主流程 | 换人登录后视图不重置：教师停在题库管理退出，管理员登入仍渲染题库页 | `watch(user.id)` 时退回首页 |
| 7 | 批量导入 | `.btn-primary` 只继承 `.btn:disabled` 的 50% 透明度，禁用的「确认导入」看上去仍是一个可点的蓝按钮，而旁边同样禁用的次要按钮已明显灰掉 | 补 `.btn-primary:disabled` 灰底灰字，与 `.btn-link:disabled` 是同一类问题的同一种解法 |
| 8 | 批量导入 | 全局样式让 `input` 占满整行，文件选择框把「下载模板」按钮挤到了第二行 | 文件输入框显式 `width: auto`，两者回到同一行 |
| 9 | 四项新功能 | 筛选栏的 `min-width: 120px` 把勾选框撑成 120 px 宽的方块，文字标签被推到很远的右边，看上去和勾选框没有关系 | 给 `.filter-bar input[type='checkbox' / 'radio']` 单独把 `min-width` 归零 |
| 10 | 四项新功能 | 错题本列表里简答题的作答动辄十几行，塞进表格一格把行高撑到半屏 | 列表里截断到 36 字，完整作答放进展开的详情行（并保留换行） |
| 11 | 四项新功能 | 主观题拿了 8/10 也在错题本里，但「我的作答」被统一标红，等于告诉学生「这答案是错的」 | 只有客观题答错才标红，主观题用正常字色、未作答用灰色 |

每批修复后都重跑了全部验证。最后一次（四项新功能落地后）的结果是：后端 55 项通过（`BUILD SUCCESS`）、前端 55 项通过、类型检查与 Lint 通过、生产构建成功（193.96 KB / gzip 62.15 KB）、注释比例 37.8%。

### 6.5 截图清单

39 张，位于 `docs/images/screenshots/`，编号即演示顺序。`23-stats-overview` 在导入功能落地后重新采集，使题库计数与当前数据一致；`28`—`30` 为批量导入的三步；`31`—`39` 为本次四项新功能（见第 8 节）：

`01-login` `02-admin-dashboard` `03-admin-users` `04-teacher-dashboard` `05-question-bank` `06-question-form` `07-ai-draft` `08-ai-draft-confirm` `09-ai-draft-saved` `10-ai-not-configured` `11-paper-compose` `12-exam-manage` `13-student-exam-list` `14-student-answering` `15-student-short-answer` `16-answer-restored` `17-submit-result` `18-grading-board` `19-grading-modal` `20-grading-scored` `21-results-published` `22-ranking-tie` `23-stats-overview` `24-system-settings` `25-token-expired` `26-student-my-results` `27-student-review` `28-import-modal` `29-import-preview` `30-import-done` `31-auto-compose` `32-auto-compose-applied` `33-paper-export` `34-paper-export-csv` `35-settings-editable` `36-settings-overridden` `37-wrong-book` `38-practice` `39-practice-result`

## 7. 题库批量导入实测记录（2026-09-04）

导入是本期新增的增强功能，因此单独记录一次真实环境验证。环境同第 6 节；接口层用 `curl` 等价的 HTTP 请求逐条核对。

### 7.1 模板与主流程

| 场景 | 实测结果 |
|---|---|
| `GET /questions/import/template` | `200 text/csv`，1210 字节，首字节是 UTF-8 BOM，表头为 `题型,题干,难度,知识点,分值,选项,标准答案,解析,标签` |
| 模板知识点不存在时预览 | `total=5 imported=0 failed=5`，每行原因为「知识点「示例知识点」不存在，请先在题库页添加这个知识点，再重新导入」 |
| 建好知识点后预览 | `dryRun=true total=5 imported=5 failed=0`，五行分别识别为单选/多选/判断/简答/编程，`questionId` 全为 `null` |
| 预览是否写库 | 题库总数不变（预览前后均为 11） |
| 正式导入 | `imported=5 failed=0`，题库总数 11 → 16，返回的 `questionId` 为 12—16 |
| 入库字段核对 | 单选：难度 `EASY`、分值 `10.0`、选项键 `A B C D`（模板里写的是 `A. int` 形式）、答案 `["A"]`；多选：答案 `["A","B"]`（表格里写的是 `AB`）；判断：无选项、答案 `true`（表格里写的是「正确」）；编程：难度 `HARD`、答案为参考文本 |

### 7.2 判重、坏行与整表级校验

| 场景 | 实测结果 |
|---|---|
| 同一份内容再导一次 | `imported=0 skipped=5 failed=0`，逐行提示「题库中已存在相同题干的题目，本行跳过」；题库总数仍为 16 |
| 文件内部有两行相同题干 | 预览与正式导入都是 `imported=1 skipped=1`，第二行提示「本次导入的前面几行已有相同题干」——预览数字与实际写入数字一致 |
| 题型写错（`火箭题`） | 第 2 行 `FAILED`「无法识别的题型「火箭题」，只能填 单选题 / 多选题 / 判断题 / 简答题 / 编程题」 |
| 标准答案指向不存在的选项（答案 `C`，只有 A、B） | 第 3 行 `FAILED`「标准答案必须引用已有选项」——**格式合法、业务不合法，只有走与手工出题相同的校验才拦得下来** |
| 分值写成 `abc` | 第 4 行 `FAILED`「分值「abc」不是数字」 |
| 判断题答案写成「也许」 | 第 5 行 `FAILED`「判断题的标准答案「也许」无法识别，请填 正确 或 错误」 |
| 坏行是否连累好行 | 同一批里的第 6 行照常写入（`imported=1`），证明逐行独立事务生效 |
| 缺必填列 | `400 IMPORT_INVALID_FORMAT`「表头缺少必填列：题型、知识点、标准答案。可先下载模板，按模板的表头填写」 |
| 只有表头 | `400 IMPORT_INVALID_FORMAT`「只有表头没有数据行」 |
| 201 行 | `400 IMPORT_TOO_MANY_ROWS`「单次最多导入 200 行，当前有 201 行，请分批导入」 |
| 空内容 | `400 VALIDATION_ERROR`（与其他接口的缺字段错误同一个码） |
| 制表符分隔 + BOM + 引号内换行 | `200 total=2 imported=2`，行号为 `2` 和 `4`——三行的多行题干正确占用 2—3 行，下一条记录的行号跳到 4 |

### 7.3 权限路径

| 场景 | 实测 |
|---|---|
| 未登录导入 / 下载模板 | `401 UNAUTHORIZED` ×2 |
| 学生导入 / 下载模板 | `403 FORBIDDEN` ×2 |
| 管理员导入 | `403 FORBIDDEN` |

导入接口挂在 `/api/v1/questions/**` 前缀下，因此直接继承 `SecurityConfig` 里「该前缀仅教师」的规则，不需要额外的方法级注解。

### 7.4 浏览器走查

在 Chrome 里粘贴一份「2 行可导入 + 1 行重复 + 1 行题型写错」的表格，预览显示 `数据行 4 / 可导入 2 / 跳过 1 / 失败 1`，确认按钮变为「确认导入 2 道」；点击后统计卡从「可导入」变为「已导入」，弹窗内提示「导入完成：成功 2 道，跳过 1 道，失败 1 道。」，页面提示「已导入 2 道题目到题库。」，题库列表刷新后出现这两道题。控制台无新增报错（页面初始的 2 条 401 来自打开时的过期令牌，与本功能无关）。本批另发现 2 个界面缺陷，见 [6.4](#64-走查中发现并修复的-11-个界面缺陷) 第 7、8 行。

## 8. 自动组卷、试卷导出、错题重练与可编辑设置实测记录（2026-09-04）

四项功能是本期第二批增强项（`plan.md` 第 2.3 节增强项 2、3、5 的后半与「可编辑系统设置」），因此单独记录一次真实环境验证。环境同第 6 节，数据库为本机 **MySQL 9.6（Flyway v5）**；接口层用等价的 HTTP 请求逐条核对，界面层在浏览器里走查并留存 9 张截图（`31`—`39`）。本批发现的 3 个界面缺陷见 [6.4](#64-走查中发现并修复的-11-个界面缺陷) 第 9—11 行。

### 8.0 V5 迁移

| 项 | 实测结果 |
|---|---|
| 应用 V5 | 非 Web 模式启动跑迁移：`Successfully validated 5 migrations` → `Migrating schema smart_exam to version "5 - add settings and practice"` → `Successfully applied 1 migration ... now at version v5`（45 ms） |
| 新增两张表 | `SHOW TABLES` 可见 `system_setting` 与 `practice_attempt`；`flyway_schema_history` 最新一行为 `5 / add settings and practice / success=1` |
| 既有数据 | 迁移只做 `CREATE TABLE`，18 道题目、7 场考试、既有答卷与成绩全部不受影响 |

### 8.1 规则自动组卷

| 场景 | 实测结果 |
|---|---|
| 两条规则（单选抽 2 每题 10 分 + 不限抽 2 每题 5 分） | `200`，`questionCount=4`、`totalScore=30`；规则 1 候选池 7、小计 20，规则 2 候选池 15、小计 10 |
| 筛选是否生效 | 规则 1 抽中的两道 `difficulty` 与 `type` 均符合条件；规则 2 抽到的题不在规则 1 已抽走的集合里 |
| 抽中题目去重 | 4 个题目 ID 互不重复，且全部为 `ACTIVE` |
| 「换一批」 | 同一组规则再抽一次得到另一组 ID（`[11,18,4,14]` → `[1,18,16,3]`），说明每次重新随机 |
| **方案原样保存** | 把方案里的 `questionId` 与 `score` 直接提交 `POST /papers`：`200`，试卷总分 `30.0`、题数 4——**证明自动组卷没有第二条写入路径** |
| 题目不够 | `400 AUTO_COMPOSE_NOT_ENOUGH`「第 1 条规则（编程题 / 难）需要 30 道题，题库当前只有 2 道可用；请减少数量、放宽条件或先补充题目」 |
| 规则之间抢题 | 第 1 条抽走全部候选后，第 2 条报错并注明「已排除前面规则抽中的题目」 |
| 超过抽题上限 | `400 AUTO_COMPOSE_TOO_MANY`（上限取自系统设置，默认 50 道） |
| 知识点不存在 | `400 KNOWLEDGE_POINT_NOT_FOUND`，而不是笼统地说题目不够 |
| 停用题目 | 停用后候选池从 2 降到 1，抽 2 道即报错——停用题不会被抽中 |
| 权限 | 学生 `403`、未登录 `401` |
| 浏览器走查 | 规则表填「单选题抽 3 每题 10 分」+「判断题抽 2 每题 5 分」→ 生成方案显示「抽中题数 5 / 方案总分 40 / 规则条数 2」，候选池 7 与 3；点「应用到试卷 5 道」后右侧试卷内容填入 5 题、总分自动汇总 40，页面提示「自动组卷已加入 5 道题，确认后请点『保存草稿』」（截图 `31`、`32`） |

### 8.2 试卷导出

| 场景 | 实测结果 |
|---|---|
| `format=md&withAnswers=false` | `200 text/markdown`，460 字符；`Content-Disposition` 含 `filename*=UTF-8''`（中文文件名按 RFC 5987 编码） |
| 不含答案是否真的不含 | 正文里检索「参考答案」为假，标准答案与解析的字面内容均不出现 |
| 大题分组 | `## 一、单选题（本大题共 2 小题，每小题 10 分，共 20 分）`、`## 二、判断题（本大题共 2 小题，每小题 5 分，共 10 分）`；分值不一致时不写「每小题 x 分」（实测另一份卷子为「共 3 小题，共 25 分」） |
| `withAnswers=true` | 卷末出现 `## 参考答案与解析`，首条为 `1. **答案：B**（10 分）`；判断题答案写成「正确」而不是 `true` |
| `format=csv` | `200 text/csv`，带 UTF-8 BOM，表头 `题型,题干,难度,知识点,分值,选项,标准答案,解析,标签`（与导入模板逐字一致）；4 题共 5 行 |
| CSV 取值形态 | 单选答案 `A`、判断题答案「正确」、选项 `A. 甲\|B. 乙` 形式、分值取试卷里的分值（判断题 5 分而非题库建议的 10 分） |
| **导出→导入回环（预览）** | 把导出的 CSV 提交给 `POST /questions/import`：`total=4 imported=0 skipped=4`，逐行提示「题库中已存在相同题干的题目，本行跳过」——**跳过发生在业务校验之后，说明 4 行全部解析成功并通过校验** |
| **导出→导入回环（写库）** | 把系统设置里的「导入跳过重复题干」关掉后正式导入：`imported=4 failed=0`；随后按返回的 `questionId` 逐个删除临时题目并把该设置恢复默认，题库回到 18 道 |
| 格式与权限 | `format=pdf` → `400 EXPORT_FORMAT_UNSUPPORTED`；学生 `403`；未登录 `401`；不存在的试卷 `404 PAPER_NOT_FOUND`；不带 `format` 默认导 Markdown |
| 浏览器走查 | 弹窗默认「Markdown + 不含答案」并直接显示预览；勾「含答案」后正文立即重新生成；切到 CSV 后「含答案」开关变灰锁定并说明「CSV 用于回导题库，『标准答案』是导入的必填列」（截图 `33`、`34`） |

### 8.3 可编辑系统设置

| 场景 | 实测结果 |
|---|---|
| 读接口 | 六项可编辑参数全部 `overridden=false`，各自给出 `value` / `defaultValue` / 范围 / 单位 / 说明 |
| **改及格线立即生效** | 管理员把 `exam.pass-ratio-percent` 从 60 改为 50：`changed=1`，该项变为「已覆盖」并记录修改人「系统管理员」；同一场考试的成绩接口及格率 **25.0 → 75.0**，统计分析页的及格分数线 **12.0 → 10.0**（同一次修改同时影响两个页面，因为两者读的是同一个实现） |
| 提交相同值 | `changed=0`——界面因此能如实说「没有改动」 |
| **恢复默认** | 传空串：`changed=1`，该项回到 `60` 且 `overridden=false`，及格率回到 **25.0**；`SELECT COUNT(*) FROM system_setting` 为 0，说明恢复默认是**删掉覆盖行**而不是写回一个当时的默认值 |
| **改导入上限立即生效** | 把 `question.import-max-rows` 改为 10 后，同一份 12 行的表格从可预览变为 `400 IMPORT_TOO_MANY_ROWS`，提示语里的数字也跟着变成「最多导入 10 行」 |
| 越界 | `400 SETTING_INVALID`「『及格线』取值范围是 0—100%」 |
| 非整数 / 非数字 / 非法布尔 | 分别提示「必须是整数」「『abc』不是数字」「只能填 true 或 false」 |
| 未知键 | `app.security.jwt-secret`、`ai.api-key` 均返回 `400 SETTING_UNKNOWN`——**密钥不在白名单里，没有任何通道能从界面改它** |
| 整批校验 | 一次提交里含一项越界时整批不生效，`system_setting` 表零行 |
| 权限 | 教师读 `200`、写 `403`；学生读写均 `403`；未登录 `401` |
| 响应不含密钥 | 完整响应体里检索测试密钥与 `jdbc:` 均为假 |
| 浏览器走查 | 管理员页面上半部分是六行可编辑参数（当前值输入框 + 来源标记 + 默认值 + 说明 + 恢复默认），保存后提示「已更新 1 项设置，立即生效，无需重启」，来源变绿色「已覆盖」并显示「系统管理员 于 … 修改」；教师登录同一页时没有任何输入框与保存按钮，右上写明「只有管理员可以修改，教师为只读」（截图 `35`、`36`） |

### 8.4 错题本与错题重练

| 场景 | 实测结果 |
|---|---|
| 错题本内容 | 学生甲在已公布成绩的考试里有 2 道未得满分：多选题 `0.0/10.0`（答 `["A"]`，标准答案 `["A","B"]`）与简答题 `8.0/10.0`（含教师评语）；`objectiveCount=1`、`subjectiveCount=1`、`practiceBatchSize=10` |
| 收录标准 | 8 分的简答题同样进错题本——**标准是「未得满分」而不是「得 0 分」** |
| 公布前 | 同一场考试在公布成绩之前，错题本 `total=0`，练习集返回 `400 PRACTICE_NO_QUESTION`（由自动化测试覆盖，见 3.1） |
| **练习集不含答案** | 响应字段只有 `examName`、`maxScore`、`options`、`paperQuestionId`、`practiceCount`、`stem`、`type` ——**没有 `standardAnswer`、`explanation`、`myAnswer`** |
| 先练错 | 提交 `["A"]`：`correctCount=0`、正确率 `0.0`，此时才返回标准答案 `["A","B"]` |
| 再练对 | 提交 `["B","A"]`：`correctCount=1`、正确率 `100.0`——多选按集合比较，顺序不影响，与交卷判分规则一致 |
| 练习记录 | 错题本随即显示 `practiceCount=2`、`lastCorrect=true`、`mastered=true`，已掌握计数 1 |
| **成绩未被改动** | 该题在考试里的得分仍是 `0.0`，答卷 `total_score` 仍是 `28.0`、`objective_score` 仍是 `20.0`、状态仍是 `GRADED`；`practice_attempt` 表有 2 行练习记录 |
| 默认只练未掌握 | 练对之后再取练习集返回 `400 PRACTICE_NO_QUESTION`；显式 `onlyUnmastered=false` 时仍可取到 1 题 |
| 主观题 | 对简答题调用提交接口 → `400 PRACTICE_NOT_OBJECTIVE`「简答题和编程题没有确定性判分规则，只能对照参考答案自行复习」 |
| 不在错题本的题 | 用一个任意题目 ID 提交 → `404 WRONG_QUESTION_NOT_FOUND`；用「未公布成绩考试里答错的题」提交同样 `404`——**成绩公布这道闸门没有被重练接口绕开**（后者由自动化测试覆盖） |
| 重复提交 | 同一道题在一次请求里出现两次 → `400 DUPLICATE_ANSWER`，且整批不留下练习记录 |
| 权限 | 教师 `403`、管理员 `403`、未登录 `401` |
| 浏览器走查 | 概况卡四个计数正确；多选题行得分标红、状态「未掌握」，简答题行状态「仅复习」；点「解析」展开完整作答、参考答案、解析与教师评语；重练界面只有客观题且看不到答案；提交后结果页给出「答对 / 正确率 100% / 参考答案 / 解析」；返回「我的成绩」核对总分仍为 28 / 40（截图 `37`、`38`、`39`） |
