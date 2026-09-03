/**
 * 统计分析与系统设置的前端单元测试。
 *
 * 三件值得测的事：
 * 1. 条形图是否按最大值等比缩放——这是自己实现图表而不用图表库后唯一需要自证的计算；
 * 2. 统计表格是否把「不适用」渲染成破折号而不是 0%——主观题没有正确率，显示 0% 会误导教师；
 * 3. 系统设置页是否只显示「已配置 / 未配置」而绝不渲染密钥。
 *
 * 数字本身的正确性由后端集成测试保证（`StatsIntegrationTest` 里写死了 28 分、70% 分段等值），
 * 前端测试只证明拿到数据后的呈现没有歧义。
 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BarChart from '../components/BarChart.vue'
import type { ExamAnalysis, QuestionStat, StatsOverview, SystemSettings } from '../api'

// 统计与设置两个页面都只读接口，把三个方法换成桩即可，其余导出保持真实实现。
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      statsOverview: vi.fn<typeof actual.api.statsOverview>(),
      examAnalysis: vi.fn<typeof actual.api.examAnalysis>(),
      exams: vi.fn<typeof actual.api.exams>(),
      systemSettings: vi.fn<typeof actual.api.systemSettings>(),
    },
  }
})
const { api } = await import('../api')
const StatsView = (await import('../views/StatsView.vue')).default
const SystemSettingsView = (await import('../views/SystemSettingsView.vue')).default

/** 等一个宏任务，让组件 onMounted 里的 await 链走完再断言。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 造一份题库概况，四道题分布在两种题型上。 */
function overview(): StatsOverview {
  return {
    bank: {
      total: 4, active: 3, disabled: 1, knowledgePointCount: 2,
      byType: [
        { key: 'SINGLE_CHOICE', label: '单选题', count: 2 },
        { key: 'MULTIPLE_CHOICE', label: '多选题', count: 1 },
        { key: 'TRUE_FALSE', label: '判断题', count: 1 },
        { key: 'SHORT_ANSWER', label: '简答题', count: 0 },
        { key: 'PROGRAMMING', label: '编程题', count: 0 },
      ],
      byDifficulty: [
        { key: 'EASY', label: '简单', count: 1 },
        { key: 'MEDIUM', label: '中等', count: 3 },
        { key: 'HARD', label: '困难', count: 0 },
      ],
      byKnowledgePoint: [{ key: '1', label: 'Java 基础', count: 4 }, { key: '2', label: '集合', count: 0 }],
    },
    activity: {
      paperCount: 2, publishedPaperCount: 1, examCount: 3, publishedExamCount: 2,
      resultsPublishedExamCount: 1, submissionCount: 4, inProgressCount: 1, pendingSubjectiveCount: 2,
    },
  }
}

/** 造一道题的统计行，默认是一道答对率 100% 的单选题。 */
function question(overrides: Partial<QuestionStat> = {}): QuestionStat {
  return {
    paperQuestionId: 11, displayOrder: 1, type: 'SINGLE_CHOICE', stem: '封装的作用', maxScore: 10,
    subjective: false, totalCount: 4, answeredCount: 4, blankCount: 0, fullMarkCount: 4,
    correctRate: 100.0, averageScore: 10.0, scoreRate: 100.0, ungradedCount: 0,
    ...overrides,
  }
}

/** 造一场考试的分析结果，数字与后端验收数据一致：总分 40、平均 28、70% 分段。 */
function analysis(questions: QuestionStat[]): ExamAnalysis {
  return {
    examId: 3, examName: 'Java 在线考试', examStatus: 'PUBLISHED', resultsPublished: false,
    paperTotalScore: 40, passScore: 24, submissionCount: 1, gradedCount: 1,
    averageScore: 28, highestScore: 28, lowestScore: 28, passRate: 100,
    distribution: [
      { label: '0—59%（不及格）', count: 0, ratio: 0 },
      { label: '60—69%', count: 0, ratio: 0 },
      { label: '70—79%', count: 1, ratio: 100 },
      { label: '80—89%', count: 0, ratio: 0 },
      { label: '90—100%', count: 0, ratio: 0 },
    ],
    questions,
  }
}

/** 造系统设置响应，默认「AI 未配置、H2 无迁移历史」这一组最容易渲染错的取值。 */
function settings(overrides: Partial<SystemSettings> = {}): SystemSettings {
  return {
    runtime: {
      service: 'smart-exam-backend', springBootVersion: '3.5.16', javaVersion: '21.0.2',
      serverTimeZone: 'Asia/Shanghai', serverTime: '2026-09-04T01:00:00Z',
    },
    security: { tokenType: 'JWT / HS256', accessTokenMinutes: 60, passwordAlgorithm: 'BCrypt', tokenRevocable: false },
    exam: {
      autoSubmitIntervalMs: 30000, passRatioPercent: 60,
      rankingRule: '竞赛排名，同分并列且占用名次（1、2、2、4）',
      partialCreditRule: '多选题答案集合完全一致才得分，不给部分分',
    },
    ai: { configured: false, protocol: 'openai', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', timeoutSeconds: 60, maxTokens: 2048 },
    database: { product: 'MySQL', version: '8.4.0', schemaVersion: null, sessionTimeZone: 'UTC（连接串强制会话时区）' },
    ...overrides,
  }
}

describe('BarChart', () => {
  /** 条形长度按最大值归一化：最大项占满，其余按比例，而不是按占总和的比例。 */
  it('按最大值等比缩放条形长度', () => {
    const wrapper = mount(BarChart, {
      props: { items: [{ label: '单选', value: 4 }, { label: '多选', value: 1 }, { label: '编程', value: 0 }] },
    })
    const styles = wrapper.findAll('.bar-fill').map((fill) => fill.attributes('style') ?? '')
    expect(styles).toEqual(['width: 100%;', 'width: 25%;', 'width: 0%;'])
  })

  /** 全为 0 时不能出现 NaN 宽度：分母兜底为 1。 */
  it('全部为 0 时宽度为 0 而不是 NaN', () => {
    const wrapper = mount(BarChart, { props: { items: [{ label: '简答', value: 0 }] } })
    expect(wrapper.find('.bar-fill').attributes('style')).toContain('width: 0%')
    expect(wrapper.html()).not.toContain('NaN')
  })

  /** 没有数据时显示可自定义的空态文案，而不是一个空白区域。 */
  it('无数据时显示空态文案', () => {
    const wrapper = mount(BarChart, { props: { items: [], emptyText: '题库还没有题目' } })
    expect(wrapper.text()).toContain('题库还没有题目')
    expect(wrapper.find('.bar-row').exists()).toBe(false)
  })
})

describe('StatsView', () => {
  /** 概况数据落到统计卡上，且计数为 0 的题型仍出现在分布图里（覆盖缺口要看得见）。 */
  it('渲染题库概况并保留计数为 0 的分组', async () => {
    vi.mocked(api.statsOverview).mockResolvedValue(overview())
    vi.mocked(api.exams).mockResolvedValue([])
    const wrapper = mount(StatsView)
    await flush()
    const text = wrapper.text()
    expect(text).toContain('题目总数')
    expect(text).toContain('编程题')
    expect(wrapper.findAll('.bar-row').length).toBeGreaterThanOrEqual(10)
  })

  /**
   * 主观题的正确率必须显示成破折号。
   *
   * 这是本页最容易出错的地方：后端给 null 表示「不适用」，若前端用 `?? 0` 兜底，
   * 教师会看到一道刚评了 8 分的简答题「正确率 0%」。
   */
  it('主观题正确率显示为破折号，低正确率客观题标红', async () => {
    vi.mocked(api.statsOverview).mockResolvedValue(overview())
    vi.mocked(api.exams).mockResolvedValue([
      { id: 3, name: 'Java 在线考试', paperId: 1, paperName: '验收卷', totalScore: 40, durationMinutes: 30, startAt: '2026-09-04T01:00:00Z', endAt: '2026-09-04T03:00:00Z', status: 'PUBLISHED' },
    ] as never)
    vi.mocked(api.examAnalysis).mockResolvedValue(analysis([
      question({ displayOrder: 1, correctRate: 40, fullMarkCount: 1, averageScore: 4, scoreRate: 40 }),
      question({
        paperQuestionId: 14, displayOrder: 2, type: 'SHORT_ANSWER', stem: '简述封装', subjective: true,
        fullMarkCount: null, correctRate: null, averageScore: 8, scoreRate: 80, ungradedCount: 0,
      }),
    ]))
    const wrapper = mount(StatsView)
    await flush()
    await flush()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    /** 取第 row 行第 index 列的单元格；越界直接抛错，比留一个 undefined 更早暴露问题。 */
    const cell = (row: number, index: number) => {
      const found = rows[row]?.findAll('td')[index]
      if (!found) throw new Error(`第 ${row} 行第 ${index} 列不存在`)
      return found
    }
    // 客观题：正确率 40% 低于 60%，单元格带告警样式。
    expect(cell(0, 5).text()).toBe('40%')
    expect(cell(0, 5).classes()).toContain('state-off')
    // 主观题：正确率不适用显示破折号，得分率仍有值。
    expect(cell(1, 5).text()).toBe('—')
    expect(cell(1, 7).text()).toBe('80%')
    // 成绩分布的五个分段都渲染出来，包含人数为 0 的段。
    expect(wrapper.text()).toContain('70—79%')
    expect(wrapper.text()).toContain('及格线 24 分')
  })
})

describe('SystemSettingsView', () => {
  /** 未配置 AI 时显示未配置与降级说明，且页面任何位置都不出现密钥字样。 */
  it('AI 未配置时显示降级说明且不渲染密钥', async () => {
    vi.mocked(api.systemSettings).mockResolvedValue(settings())
    const wrapper = mount(SystemSettingsView)
    await flush()
    const text = wrapper.text()
    expect(text).toContain('未配置')
    expect(text).toContain('手工出题与考试主流程不受影响')
    expect(text).toContain('只存于后端环境变量')
    // 响应结构里没有密钥字段，页面自然也不该出现任何看起来像密钥的值。
    expect(wrapper.html()).not.toMatch(/sk-|api[-_]?key["']?\s*[:=]/i)
  })

  /** 没有 Flyway 历史时如实写「未使用 Flyway」，不能编一个版本号。 */
  it('没有迁移历史时显示未使用 Flyway', async () => {
    vi.mocked(api.systemSettings).mockResolvedValue(settings())
    const wrapper = mount(SystemSettingsView)
    await flush()
    expect(wrapper.text()).toContain('未使用 Flyway')
  })

  /** 有迁移历史时显示版本号，令牌吊销状态如实显示为「不维护吊销名单」。 */
  it('显示迁移版本与令牌吊销的真实状态', async () => {
    vi.mocked(api.systemSettings).mockResolvedValue(settings({
      database: { product: 'MySQL', version: '9.6.0', schemaVersion: '4', sessionTimeZone: 'UTC（连接串强制会话时区）' },
    }))
    const wrapper = mount(SystemSettingsView)
    await flush()
    expect(wrapper.text()).toContain('Flyway v4')
    expect(wrapper.text()).toContain('不维护吊销名单')
  })
})
