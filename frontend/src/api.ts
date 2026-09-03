/**
 * 后端 REST 接口的类型定义与调用封装。
 *
 * 全项目只有这一个文件直接调用 fetch，视图层一律通过 `api.*` 访问后端。这样做的好处是：
 * 令牌注入、错误结构解析、401 处理和响应解包只需要在一处维护，接口契约变化时也只改这里。
 * 契约细节以 `docs/api.md` 为准。
 */

/** 接口基地址。开发时由 Vite 代理把 /api 转发到后端，生产环境由同源反向代理转发。 */
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api'

/** 用户角色。 */
export type Role = 'ADMIN' | 'TEACHER' | 'STUDENT'
/** 题型。SHORT_ANSWER 与 PROGRAMMING 是主观题，只能由教师人工评分。 */
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'PROGRAMMING'
/** 难度，仅用于筛选与组卷时的人工判断，不参与分值计算。 */
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'

/** 当前登录用户的公开信息，不含密码等敏感字段。 */
export interface User { id: number; username: string; displayName: string; role: Role }

/** 知识点，题目的分类依据，也是组卷时的筛选维度。 */
export interface KnowledgePoint { id: number; name: string; description?: string | null }

/** 选择题的一个选项。`key` 在同一题内唯一，用于和标准答案比对。 */
export interface Option { key: string; content: string }

/**
 * 题库中的题目。
 *
 * `standardAnswer` 的类型随题型变化，因此声明为 unknown，由使用处按题型收窄：
 * 选择题是选项键数组，判断题是布尔值，简答题和编程题是字符串。
 */
export interface Question {
  id: number
  type: QuestionType
  stem: string
  difficulty: Difficulty
  /** 关键词标签，后端已规范化为 `a, b` 形式；仅用于展示和检索。 */
  tags?: string | null
  standardAnswer: unknown
  explanation?: string | null
  /** 题库里的建议分值。组卷时会作为默认值填入，但可以逐题改。 */
  suggestedScore: number
  knowledgePointId: number
  knowledgePointName: string
  /** DISABLED 的题目仍会出现在题库列表里，但不能加入新试卷。 */
  status: 'ACTIVE' | 'DISABLED'
  options: Option[]
}

/** 新增或编辑题目的请求体。与 Question 的差别是不含服务端决定的字段（id、状态、创建者）。 */
export interface QuestionPayload {
  type: QuestionType
  stem: string
  difficulty: Difficulty
  tags: string | null
  standardAnswer: unknown
  explanation: string | null
  suggestedScore: number
  knowledgePointId: number
  options: Option[]
}

/**
 * 试卷中的一道题（快照）。
 *
 * 注意 `id` 是「试卷题目 ID」而不是题库里的题目 ID：保存答案时必须用这个 id 定位，
 * 因为同一道题可以出现在多份试卷里。响应里刻意不含标准答案，避免答题页拿到正确选项。
 */
export interface PaperQuestion {
  id: number
  questionId: number
  displayOrder: number
  /** 这道题在本试卷中的实际分值，与题库建议分值相互独立。 */
  score: number
  type: QuestionType
  stem: string
  options: Option[] | null
}

/** 试卷。只有 PUBLISHED 状态的试卷能用于创建考试。 */
export interface Paper {
  id: number
  name: string
  durationMinutes: number
  totalScore: number
  status: 'DRAFT' | 'PUBLISHED'
  questions: PaperQuestion[]
}

/** 一场考试。startAt / endAt 是带时区的 ISO 8601 字符串，展示时由前端转本地时区。 */
export interface Exam {
  id: number
  name: string
  paperId: number
  paperName: string
  durationMinutes: number
  totalScore: number
  startAt: string
  endAt: string
  status: string
  /** 当前学生在这场考试的答卷 ID；尚未开始作答时为 null。 */
  submissionId: number | null
  /** 答卷状态；未开始作答时为 null，前端据此显示「进入考试 / 继续作答 / 已交卷」。 */
  submissionStatus: string | null
}

/** 服务端已保存的单题作答，用于刷新或换设备后恢复答题界面。 */
export interface SavedAnswer { paperQuestionId: number; answerContent: unknown }

/**
 * 答卷。`startedAt` 由服务端写入，是倒计时的起点。
 *
 * `savedAnswers` 是服务端已保存的作答，进入答题页时优先用它恢复界面——
 * 它才是超时自动交卷实际判分的那一份，本地草稿只作为离线兜底。
 */
export interface Submission {
  id: number
  examId: number
  status: string
  startedAt: string
  submittedAt?: string | null
  objectiveScore: number
  questions: PaperQuestion[]
  savedAnswers: SavedAnswer[]
}

/**
 * 答卷里的一道题及其作答、得分与评语。
 *
 * 可空字段是刻意的：成绩公布前学生拿到的 `standardAnswer`、`score`、`gradingComment`
 * 一律是 null。`null` 表示「不给你看」，`0` 表示「这题真的是 0 分」，两者不能混。
 */
export interface AnswerDetail {
  id: number
  paperQuestionId: number
  displayOrder: number
  type: QuestionType
  stem: string
  options: Option[] | null
  /** 该题在本试卷中的满分，也是教师评分的上界。 */
  maxScore: number
  answerContent: unknown
  standardAnswer: unknown | null
  explanation: string | null
  score: number | null
  gradingComment: string | null
  gradedAt: string | null
  /** 主观题需要教师人工评分，客观题由系统判定，界面据此决定是否显示评分框。 */
  subjective: boolean
}

/** 答卷详情。教师阅卷、学生查分共用同一结构，字段按角色和公布状态裁剪。 */
export interface SubmissionDetail {
  id: number
  examId: number
  examName: string
  studentId: number
  studentName: string
  status: string
  startedAt: string
  submittedAt: string | null
  paperTotalScore: number
  objectiveScore: number | null
  subjectiveScore: number | null
  totalScore: number | null
  resultsPublished: boolean
  rank: number | null
  answers: AnswerDetail[]
}

/** 阅卷面板里的一行。`pendingCount` 为 0 表示这份答卷已批完。 */
export interface GradingItem {
  submissionId: number
  studentId: number
  studentName: string
  status: string
  submittedAt: string | null
  objectiveScore: number
  subjectiveScore: number
  totalScore: number
  subjectiveCount: number
  pendingCount: number
}

/** 阅卷面板。`inProgressCount` 或 `pendingCount` 大于 0 时不能公布成绩。 */
export interface GradingBoard {
  examId: number
  examName: string
  paperTotalScore: number
  examStatus: string
  resultsPublished: boolean
  submissionCount: number
  inProgressCount: number
  pendingCount: number
  items: GradingItem[]
}

/** 排名中的一行。同分并列且占用名次，形成 1、2、2、4。 */
export interface RankingItem {
  rank: number
  submissionId: number
  studentId: number
  studentName: string
  totalScore: number
  objectiveScore: number
  subjectiveScore: number
}

/** 教师视角的成绩统计与完整排名。统计只包含已评完的答卷。 */
export interface ExamResults {
  examId: number
  examName: string
  paperTotalScore: number
  examStatus: string
  resultsPublished: boolean
  resultsPublishedAt: string | null
  gradedCount: number
  averageScore: number | null
  highestScore: number | null
  lowestScore: number | null
  /** 及格率，按「总分 ≥ 试卷总分 60%」计算。 */
  passRate: number | null
  rankings: RankingItem[]
}

/** 学生视角的一条成绩。只有本人名次和总人数，不含其他学生信息。 */
export interface MyResult {
  submissionId: number
  examId: number
  examName: string
  submittedAt: string | null
  objectiveScore: number
  subjectiveScore: number
  totalScore: number
  paperTotalScore: number
  rank: number
  totalCount: number
}

/** AI 出题请求。题型、难度、知识点和分值由教师指定，模型只负责题干、选项、答案和解析。 */
export interface AiDraftQuery {
  knowledgePointId: number
  type: QuestionType
  difficulty: Difficulty
  /** 生成数量，1–5。上限低是因为教师要逐题确认。 */
  count?: number
  requirement?: string | null
  suggestedScore?: number
}

/**
 * AI 出题结果。
 *
 * `drafts` 直接复用 QuestionPayload：教师确认后原样提交到题目新增接口，
 * 不需要再做字段映射，也保证草稿一定走完整的业务校验。
 *
 * `warnings` 是被丢弃的草稿及原因——要了 3 道只回来 2 道时，教师需要知道为什么。
 */
export interface AiDraftResult {
  protocol: string
  model: string
  drafts: QuestionPayload[]
  warnings: string[]
}

/** 通用分组计数。题型、难度、知识点三种分布共用一个结构，图表组件因此只需要一套渲染逻辑。 */
export interface GroupCount { key: string; label: string; count: number }

/** 题库概况。`byType` 与 `byDifficulty` 由后端补齐计数为 0 的分组并固定顺序，前端直接按顺序渲染。 */
export interface BankOverview {
  total: number
  active: number
  disabled: number
  knowledgePointCount: number
  byType: GroupCount[]
  byDifficulty: GroupCount[]
  byKnowledgePoint: GroupCount[]
}

/** 教学活动概况，范围是当前教师自己创建的试卷与考试。 */
export interface ActivityOverview {
  paperCount: number
  publishedPaperCount: number
  examCount: number
  publishedExamCount: number
  resultsPublishedExamCount: number
  submissionCount: number
  inProgressCount: number
  pendingSubjectiveCount: number
}

/** 统计分析首屏数据。 */
export interface StatsOverview { bank: BankOverview; activity: ActivityOverview }

/** 成绩分布的一个分数段。分段按占试卷满分的百分比，`ratio` 是该段占已评完答卷的百分比。 */
export interface ScoreBucket { label: string; count: number; ratio: number }

/**
 * 逐题作答统计。
 *
 * `correctRate` 和 `fullMarkCount` 只有客观题有值：主观题没有「对错」，用 `scoreRate` 衡量。
 * `averageScore` 为 null 表示这一题还没有可统计的得分（主观题一份都没批）。
 */
export interface QuestionStat {
  paperQuestionId: number
  displayOrder: number
  type: QuestionType
  stem: string
  maxScore: number
  subjective: boolean
  totalCount: number
  answeredCount: number
  blankCount: number
  fullMarkCount: number | null
  correctRate: number | null
  averageScore: number | null
  scoreRate: number | null
  ungradedCount: number
}

/** 单场考试的分析结果：整体统计 + 成绩分布 + 逐题正确率。 */
export interface ExamAnalysis {
  examId: number
  examName: string
  examStatus: string
  resultsPublished: boolean
  paperTotalScore: number
  passScore: number
  submissionCount: number
  gradedCount: number
  averageScore: number | null
  highestScore: number | null
  lowestScore: number | null
  passRate: number | null
  distribution: ScoreBucket[]
  questions: QuestionStat[]
}

/**
 * 系统设置：当前进程真正生效的运行参数，只读。
 *
 * `ai.configured` 是布尔值而不是密钥：密钥只存在于后端环境变量，任何接口都不下发。
 * `database.schemaVersion` 为 null 表示没有 Flyway 迁移历史（例如自动化测试用的 H2 手写 schema）。
 */
export interface SystemSettings {
  runtime: { service: string; springBootVersion: string; javaVersion: string; serverTimeZone: string; serverTime: string }
  security: { tokenType: string; accessTokenMinutes: number; passwordAlgorithm: string; tokenRevocable: boolean }
  exam: { autoSubmitIntervalMs: number; passRatioPercent: number; rankingRule: string; partialCreditRule: string }
  ai: { configured: boolean; protocol: string; baseUrl: string; model: string; timeoutSeconds: number; maxTokens: number }
  database: { product: string; version: string; schemaVersion: string | null; sessionTimeZone: string }
}

/** 管理员看到的用户行。响应里没有任何密码字段。 */
export interface AdminUser {
  id: number
  username: string
  displayName: string
  role: Role
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string | null
}

/** 分页响应。`total` 是符合条件的总条数，不是当前页条数。 */
export interface Page<T> { items: T[]; page: number; size: number; total: number }

/** 后端统一错误结构，见 docs/api.md 第 1 节。 */
export interface ApiError extends Error { status: number; code?: string; fieldErrors?: Record<string, string> }

/** 题库列表的查询条件，全部可选；未提供的条件后端不加入 WHERE。 */
export interface QuestionQuery {
  keyword?: string
  type?: string
  difficulty?: string
  knowledgePointId?: string
  status?: string
  page?: number
  size?: number
}

/** 收到 401 时通知外层清理登录态，避免页面停留在“已登录但令牌失效”的死状态。 */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler }

/**
 * 统一的请求入口，集中处理四件事：
 * 1. 有请求体时才设置 Content-Type，避免给 GET 请求加上多余的头；
 * 2. 有令牌时注入 Authorization；
 * 3. 失败时解析后端的统一错误结构，把 status / code / fieldErrors 一起带给调用方；
 * 4. 成功时剥掉外层的 `data` 包装，让调用处直接拿到业务数据。
 *
 * 204 没有响应体，必须单独判断，否则 `response.json()` 会抛解析错误。
 */
async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as {
      message?: string
      code?: string
      fieldErrors?: Record<string, string>
    }
    if (response.status === 401 && token) onUnauthorized?.()
    const error = new Error(body.message || `请求失败（${response.status}）`) as ApiError
    error.status = response.status
    error.code = body.code
    error.fieldErrors = body.fieldErrors
    throw error
  }
  if (response.status === 204) return undefined as T
  return ((await response.json()) as { data: T }).data
}

/** 拼查询串，跳过空值。空字符串也跳过，这样「全部」筛选项不会变成 `type=` 这种无意义参数。 */
function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

/**
 * 接口集合。每个方法对应 `docs/api.md` 里的一行，参数顺序统一为「令牌 → 路径参数 → 请求体」。
 */
export const api = {
  login: (username: string, password: string) =>
    request<{ accessToken: string; expiresAt: string; user: User }>('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  /** 校验令牌是否仍然有效，并返回最新的用户信息。 */
  me: (token: string) => request<User>('/v1/auth/me', {}, token),
  logout: (token: string) => request<void>('/v1/auth/logout', { method: 'POST' }, token),

  /** 知识点全量列表，用于筛选下拉框和题目表单。 */
  points: (token: string) => request<KnowledgePoint[]>('/v1/knowledge-points', {}, token),
  createPoint: (token: string, name: string, description?: string | null) =>
    request<KnowledgePoint>('/v1/knowledge-points', {
      method: 'POST',
      body: JSON.stringify({ name, description: description ?? null }),
    }, token),
  updatePoint: (token: string, id: number, name: string, description?: string | null) =>
    request<KnowledgePoint>(`/v1/knowledge-points/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ name, description: description ?? null }),
    }, token),
  deletePoint: (token: string, id: number) =>
    request<void>(`/v1/knowledge-points/${id}`, { method: 'DELETE' }, token),

  /** 分页查询题目。size 上限 100，由后端校验。 */
  questions: (token: string, params: QuestionQuery) =>
    request<Page<Question>>(`/v1/questions${query({ ...params })}`, {}, token),
  question: (token: string, id: number) => request<Question>(`/v1/questions/${id}`, {}, token),
  createQuestion: (token: string, body: QuestionPayload) =>
    request<Question>('/v1/questions', { method: 'POST', body: JSON.stringify(body) }, token),
  updateQuestion: (token: string, id: number, body: QuestionPayload) =>
    request<Question>(`/v1/questions/${id}`, { method: 'PUT', body: JSON.stringify(body) }, token),
  /** 启用或停用题目。停用后不能加入新试卷，历史试卷快照不受影响。 */
  setQuestionStatus: (token: string, id: number, status: 'ACTIVE' | 'DISABLED') =>
    request<Question>(`/v1/questions/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }, token),
  /** 删除题目。已被试卷引用时后端会降级为停用，而不是真的删除。 */
  deleteQuestion: (token: string, id: number) =>
    request<void>(`/v1/questions/${id}`, { method: 'DELETE' }, token),

  papers: (token: string) => request<Paper[]>('/v1/papers', {}, token),
  paper: (token: string, id: number) => request<Paper>(`/v1/papers/${id}`, {}, token),
  /** 创建试卷草稿。totalScore 必须严格等于各题分值之和，否则后端返回 PAPER_SCORE_MISMATCH。 */
  createPaper: (
    token: string,
    body: { name: string; durationMinutes: number; totalScore: number; questions: { questionId: number; score: number }[] },
  ) => request<Paper>('/v1/papers', { method: 'POST', body: JSON.stringify(body) }, token),
  /** 发布试卷。发布后不可修改，只能重新组一份新的。 */
  publishPaper: (token: string, id: number) =>
    request<Paper>(`/v1/papers/${id}/publish`, { method: 'POST' }, token),

  /** 考试列表。同一个接口按角色返回不同内容：教师看自己创建的，学生看已发布的。 */
  exams: (token: string) => request<Exam[]>('/v1/exams', {}, token),
  createExam: (token: string, body: { name: string; paperId: number; startAt: string; endAt: string }) =>
    request<Exam>('/v1/exams', { method: 'POST', body: JSON.stringify(body) }, token),
  publishExam: (token: string, id: number) =>
    request<Exam>(`/v1/exams/${id}/publish`, { method: 'POST' }, token),

  /** 开始作答。幂等：已有未交卷答卷时返回原答卷，不会重置倒计时起点。 */
  startSubmission: (token: string, examId: number) =>
    request<Submission>(`/v1/exams/${examId}/submissions`, { method: 'POST' }, token),
  /** 保存整卷答案，可反复调用。未作答的题目传 null。 */
  saveAnswers: (token: string, submissionId: number, answers: { paperQuestionId: number; answerContent: unknown }[]) =>
    request<Submission>(`/v1/submissions/${submissionId}/answers`, {
      method: 'PUT',
      body: JSON.stringify({ answers }),
    }, token),
  /** 交卷。后端在同一事务内判客观题，重复交卷返回 409。 */
  submit: (token: string, submissionId: number) =>
    request<{ id: number; status: string; submittedAt: string; objectiveScore: number }>(
      `/v1/submissions/${submissionId}/submit`, { method: 'POST' }, token),

  /** 阅卷面板：某场考试的答卷列表与每份的待批数量。 */
  gradingBoard: (token: string, examId: number) =>
    request<GradingBoard>(`/v1/exams/${examId}/grading`, {}, token),
  /**
   * 答卷详情。教师读到全部字段，学生只能读本人答卷，且成绩公布前不含分数与答案。
   * 裁剪在后端完成，前端拿到的就已经是可见范围。
   */
  submissionDetail: (token: string, submissionId: number) =>
    request<SubmissionDetail>(`/v1/submissions/${submissionId}`, {}, token),
  /** 主观题评分。返回重算后的整份答卷，界面直接用它刷新总分和待批数量。 */
  scoreAnswer: (token: string, answerId: number, score: number, comment: string | null) =>
    request<SubmissionDetail>(`/v1/submission-answers/${answerId}/score`, {
      method: 'PUT',
      body: JSON.stringify({ score, comment }),
    }, token),
  /** 公布成绩。要求无人在作答且主观题已批完；公布后评分冻结、考试关闭。 */
  publishResults: (token: string, examId: number) =>
    request<ExamResults>(`/v1/exams/${examId}/publish-results`, { method: 'POST' }, token),
  /** 教师查看统计与完整排名，公布前也可调用。 */
  examResults: (token: string, examId: number) =>
    request<ExamResults>(`/v1/exams/${examId}/results`, {}, token),

  /** 统计分析首屏：题库分布与教学活动概况，范围为当前教师自己的数据。 */
  statsOverview: (token: string) => request<StatsOverview>('/v1/stats/overview', {}, token),
  /** 单场考试的成绩分布与逐题正确率。整体统计与成绩管理页同源，不会出现两套数字。 */
  examAnalysis: (token: string, examId: number) =>
    request<ExamAnalysis>(`/v1/stats/exams/${examId}`, {}, token),
  /** 系统设置：只读的运行时信息，教师与管理员都可访问；响应不含任何密钥。 */
  systemSettings: (token: string) => request<SystemSettings>('/v1/system/settings', {}, token),

  /** 学生查看本人已公布的成绩列表。 */
  myResults: (token: string) => request<MyResult[]>('/v1/my/results', {}, token),
  /** 学生查看本人某份答卷的完整详情，含标准答案、解析和教师评语。 */
  myResult: (token: string, submissionId: number) =>
    request<SubmissionDetail>(`/v1/my/results/${submissionId}`, {}, token),

  /**
   * 生成题目草稿。草稿不入库：教师确认后仍走 createQuestion 保存。
   *
   * 密钥只存在于后端环境变量，前端既不发送也不接收任何密钥。
   */
  generateDrafts: (token: string, body: AiDraftQuery) =>
    request<AiDraftResult>('/v1/ai/question-drafts', { method: 'POST', body: JSON.stringify(body) }, token),

  /** 管理员分页查询用户，支持关键词、角色和状态筛选。 */
  users: (token: string, params: { keyword?: string; role?: string; status?: string; page?: number; size?: number }) =>
    request<Page<AdminUser>>(`/v1/users${query({ ...params })}`, {}, token),
  /** 管理员新增用户。密码由后端哈希，响应里不含任何密码字段。 */
  createUser: (token: string, body: { username: string; password: string; displayName: string; role: Role }) =>
    request<AdminUser>('/v1/users', { method: 'POST', body: JSON.stringify(body) }, token),
  /** 管理员启用或停用用户。停用后旧令牌立即失效，且不能停用自己。 */
  setUserStatus: (token: string, id: number, status: 'ACTIVE' | 'DISABLED') =>
    request<AdminUser>(`/v1/users/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }, token),
}

/** 题型、难度的中文标签，界面各处统一取用。 */
export const typeLabels: Record<QuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTIPLE_CHOICE: '多选题',
  TRUE_FALSE: '判断题',
  SHORT_ANSWER: '简答题',
  PROGRAMMING: '编程题',
}
/** 难度中文标签，取单字以适配表格窄列。 */
export const difficultyLabels: Record<Difficulty, string> = { EASY: '易', MEDIUM: '中', HARD: '难' }

/** 角色中文标签，用于顶栏展示。 */
export const roleLabels: Record<Role, string> = { ADMIN: '管理员', TEACHER: '教师', STUDENT: '学生' }

/**
 * 考试状态中文标签。
 *
 * 数据库定义了六种状态，当前实现用到 DRAFT、PUBLISHED 和 RESULTS_PUBLISHED 三种；
 * 其余三种保留在映射里，避免将来一旦用上就在界面里露出英文枚举名。
 */
export const examStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  IN_PROGRESS: '进行中',
  ENDED: '已结束',
  GRADED: '已完成阅卷',
  RESULTS_PUBLISHED: '已公布成绩',
}

/** 答卷状态中文标签。 */
export const submissionStatusLabels: Record<string, string> = {
  IN_PROGRESS: '作答中',
  SUBMITTED: '已交卷',
  GRADED: '已评分',
}

/** 选择题需要选项，主观题不需要；多处校验和渲染都依赖这个判断。 */
/** 是否为选择题。选择题需要选项和选项键答案，其他题型不能带选项。 */
export const isChoice = (type: QuestionType) => type === 'SINGLE_CHOICE' || type === 'MULTIPLE_CHOICE'

/** 是否为主观题。主观题不参与客观题自动判分，交卷后等待教师批阅。 */
export const isSubjective = (type: QuestionType) => type === 'SHORT_ANSWER' || type === 'PROGRAMMING'
