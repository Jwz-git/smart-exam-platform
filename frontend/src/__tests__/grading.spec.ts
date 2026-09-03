/**
 * 阅卷与成绩相关的前端单元测试。
 *
 * 这里只测两件不依赖后端的事：答案格式化的边界，以及答卷弹窗对「不可见字段」的处理。
 * 权限与裁剪的正确性由后端集成测试保证——前端测试无法证明服务端没下发分数，
 * 但可以证明前端在拿到 null 时不会把它渲染成 0 分。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SubmissionGradeModal from '../views/SubmissionGradeModal.vue'
import { formatAnswer, formatScore, formatWhen } from '../format'
import type { AnswerDetail, SubmissionDetail } from '../api'

/** 造一道题的答案明细，只覆盖用例关心的字段，其余给默认值。 */
function answer(overrides: Partial<AnswerDetail> = {}): AnswerDetail {
  return {
    id: 1,
    paperQuestionId: 11,
    displayOrder: 1,
    type: 'SHORT_ANSWER',
    stem: '简述封装的作用',
    options: null,
    maxScore: 10,
    answerContent: '隐藏内部实现',
    standardAnswer: null,
    explanation: null,
    score: null,
    gradingComment: null,
    gradedAt: null,
    subjective: true,
    ...overrides,
  }
}

/** 造一份答卷详情，默认是「成绩未公布的学生视图」，即所有分数字段为 null。 */
function detail(overrides: Partial<SubmissionDetail> = {}): SubmissionDetail {
  return {
    id: 5,
    examId: 3,
    examName: 'Java 在线考试',
    studentId: 7,
    studentName: '学生甲',
    status: 'SUBMITTED',
    startedAt: '2026-09-04T01:00:00Z',
    submittedAt: '2026-09-04T01:20:00Z',
    paperTotalScore: 40,
    objectiveScore: null,
    subjectiveScore: null,
    totalScore: null,
    resultsPublished: false,
    rank: null,
    answers: [answer()],
    ...overrides,
  }
}

describe('formatAnswer', () => {
  /** 判断题的 false 是有效作答，不能被当成「未作答」。 */
  it('区分未作答、判断题的假值和空数组', () => {
    expect(formatAnswer(null)).toBe('未作答')
    expect(formatAnswer([])).toBe('未作答')
    expect(formatAnswer('   ')).toBe('未作答')
    expect(formatAnswer(false)).toBe('错误')
    expect(formatAnswer(true)).toBe('正确')
  })

  /** 传入选项时把选项键拼上内容，便于对照学生选了哪一项；没传就只显示键。 */
  it('选项键按需拼上选项内容', () => {
    const options = [{ key: 'A', content: '封装' }, { key: 'B', content: '继承' }]
    expect(formatAnswer(['A', 'B'], options)).toBe('A. 封装；B. 继承')
    expect(formatAnswer(['A', 'C'], options)).toBe('A. 封装；C')
    expect(formatAnswer(['A'])).toBe('A')
  })
})

describe('formatScore', () => {
  /** null 是「不可见或未评分」，0 是真实的 0 分，两者必须显示成不同的东西。 */
  it('把 null 显示为破折号，0 分照常显示', () => {
    expect(formatScore(null)).toBe('—')
    expect(formatScore(undefined)).toBe('—')
    expect(formatScore(0)).toBe('0')
    expect(formatScore(8)).toBe('8')
  })
})

describe('formatWhen', () => {
  /** 未交卷时时间为 null，显示破折号而不是 Invalid Date。 */
  it('空时间显示为破折号', () => {
    expect(formatWhen(null)).toBe('—')
    expect(formatWhen(undefined)).toBe('—')
    expect(formatWhen('2026-09-04T01:20:00Z')).not.toBe('—')
  })
})

describe('SubmissionGradeModal', () => {
  /**
   * 成绩未公布的学生视图：分数区全是破折号，不出现标准答案、评语和评分框。
   *
   * 这条测试防的是一类具体退化：把 `score ?? 0` 写进模板，让未公布的答卷显示成 0 分。
   */
  it('分数为 null 时显示破折号且不渲染评分框', () => {
    const wrapper = mount(SubmissionGradeModal, { props: { detail: detail(), gradable: false } })
    expect(wrapper.text()).toContain('隐藏内部实现')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('成绩尚未公布')
    // 只能断言「没有标准答案那一行」：上面那句提示文案本身就包含「标准答案」四个字。
    expect(wrapper.findAll('.answer-line .label').map((node) => node.text())).not.toContain('标准答案')
    expect(wrapper.find('.grade-box').exists()).toBe(false)
  })

  /** 教师阅卷视图：主观题出现评分框，已评分数回填进输入框。 */
  it('可评分时渲染评分框并回填已评分数', () => {
    const graded = detail({
      objectiveScore: 20,
      subjectiveScore: 8,
      totalScore: 28,
      answers: [answer({ score: 8, gradedAt: '2026-09-04T02:00:00Z', gradingComment: '思路正确' })],
    })
    const wrapper = mount(SubmissionGradeModal, { props: { detail: graded, gradable: true } })
    expect(wrapper.find('.grade-box').exists()).toBe(true)
    expect((wrapper.find('.grade-box input[type="number"]').element as HTMLInputElement).value).toBe('8')
    expect(wrapper.text()).toContain('已评 8 分')
  })

  /** 超过该题满分时只提示错误，不向父组件抛出 score 事件。 */
  it('得分超过满分时不提交评分', async () => {
    const wrapper = mount(SubmissionGradeModal, { props: { detail: detail(), gradable: true } })
    const input = wrapper.find('.grade-box input[type="number"]')
    await input.setValue('11')
    await wrapper.find('.grade-box .btn-primary').trigger('click')
    expect(wrapper.emitted('score')).toBeUndefined()
    expect(wrapper.text()).toContain('得分不能超过该题满分 10')
  })

  /** 合法分数抛出 score 事件，参数为「答案 ID、分数、评语」。 */
  it('合法得分抛出 score 事件', async () => {
    const wrapper = mount(SubmissionGradeModal, { props: { detail: detail(), gradable: true } })
    await wrapper.find('.grade-box input[type="number"]').setValue('8')
    await wrapper.find('.grade-box input[maxlength="1000"]').setValue('思路正确')
    await wrapper.find('.grade-box .btn-primary').trigger('click')
    expect(wrapper.emitted('score')?.[0]).toEqual([1, 8, '思路正确'])
  })
})
