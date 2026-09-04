/**
 * 错题本与错题重练的前端单元测试。
 *
 * 守三件事，每一件都对应一个「界面写错就会误导学生」的地方：
 * 1. **重练时界面上不能出现标准答案。** 接口本身不下发，前端也不能从别处渲染出来——
 *    否则重练就是抄一遍答案；
 * 2. **「未作答」和「答错」要分开显示。** 两者在成绩上都是 0 分，但复习时的含义完全不同；
 * 3. **主观题只能复习。** 界面上要标成「仅复习」并且不算进可重练的题数。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import WrongQuestionBook from '../views/WrongQuestionBook.vue'
import type { PracticeResult, PracticeSet, WrongBook, WrongQuestion } from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      wrongBook: vi.fn<typeof actual.api.wrongBook>(),
      practiceSet: vi.fn<typeof actual.api.practiceSet>(),
      submitPractice: vi.fn<typeof actual.api.submitPractice>(),
    },
  }
})
const { api } = await import('../api')
const wrongBook = vi.mocked(api.wrongBook)
const practiceSet = vi.mocked(api.practiceSet)
const submitPractice = vi.mocked(api.submitPractice)

/** 一道答错的多选题：答案是 A、B，学生只选了 A，得 0 分。 */
function multiple(overrides: Partial<WrongQuestion> = {}): WrongQuestion {
  return {
    answerId: 101, submissionId: 5, paperQuestionId: 21, examId: 3, examName: 'Java 在线考试',
    displayOrder: 2, type: 'MULTIPLE_CHOICE', stem: '下列哪些属于 List 的实现类？',
    options: [{ key: 'A', content: 'ArrayList' }, { key: 'B', content: 'LinkedList' },
      { key: 'C', content: 'HashMap' }],
    maxScore: 10, score: 0, myAnswer: ['A'], standardAnswer: ['A', 'B'],
    explanation: '多选题答案集合完全一致才得分。', gradingComment: null,
    submittedAt: '2026-09-04T02:00:00Z', subjective: false, blank: false,
    practiceCount: 0, lastCorrect: null, lastPracticedAt: null, mastered: false,
    ...overrides,
  }
}

/** 一道只得 8 分的简答题：未得满分同样进错题本，但只能复习。 */
function subjective(): WrongQuestion {
  return {
    ...multiple(),
    answerId: 102, paperQuestionId: 24, displayOrder: 4, type: 'SHORT_ANSWER',
    stem: '简述封装的作用。', options: null, score: 8, myAnswer: '隐藏实现细节',
    standardAnswer: '隐藏实现细节并只暴露必要接口。', gradingComment: '缺少异常处理',
    subjective: true, blank: false,
  }
}

/** 一道漏答的判断题。 */
function blank(): WrongQuestion {
  return {
    ...multiple(),
    answerId: 103, paperQuestionId: 25, displayOrder: 5, type: 'TRUE_FALSE',
    stem: 'String 对象不可修改。', options: null, score: 0, myAnswer: null,
    standardAnswer: true, explanation: null, blank: true,
  }
}

function book(items: WrongQuestion[], overrides: Partial<WrongBook> = {}): WrongBook {
  return {
    total: items.length,
    objectiveCount: items.filter((item) => !item.subjective).length,
    subjectiveCount: items.filter((item) => item.subjective).length,
    masteredCount: items.filter((item) => item.mastered).length,
    practiceBatchSize: 10,
    items,
    ...overrides,
  }
}

/** 练习集：注意这里没有 standardAnswer / explanation / myAnswer 三个字段。 */
function set(): PracticeSet {
  return {
    size: 1,
    questions: [{
      paperQuestionId: 21, type: 'MULTIPLE_CHOICE', stem: '下列哪些属于 List 的实现类？',
      options: [{ key: 'A', content: 'ArrayList' }, { key: 'B', content: 'LinkedList' },
        { key: 'C', content: 'HashMap' }],
      maxScore: 10, examName: 'Java 在线考试', practiceCount: 0,
    }],
  }
}

function result(correct: boolean): PracticeResult {
  return {
    total: 1, correctCount: correct ? 1 : 0, accuracy: correct ? 100 : 0,
    answers: [{
      paperQuestionId: 21, stem: '下列哪些属于 List 的实现类？', correct,
      myAnswer: correct ? ['A', 'B'] : ['A'], standardAnswer: ['A', 'B'],
      explanation: '多选题答案集合完全一致才得分。',
    }],
  }
}

/** 每个用例都从零次调用开始，「练完刷新一次错题本」这类断言才数得准。 */
beforeEach(() => {
  wrongBook.mockReset()
  practiceSet.mockReset()
  submitPractice.mockReset()
})

function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text().startsWith(text))
  if (!found) throw new Error(`找不到按钮：${text}`)
  return found
}

describe('WrongQuestionBook', () => {
  /** 概况计数分三类，主观题标成「仅复习」且不算进可重练题数。 */
  it('区分可重练的客观题与只能复习的主观题', async () => {
    wrongBook.mockResolvedValue(book([multiple(), subjective()]))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('简述封装的作用'))

    const text = wrapper.text()
    expect(text).toContain('仅复习')
    expect(text).toContain('未掌握')
    // 8 分的简答题同样是错题：收录标准是「未得满分」，不是「得 0 分」。
    expect(text).toContain('8')
    expect(button(wrapper, '开始重练').text()).toContain('1')
  })

  /** 未作答显示成「未作答」而不是空白或 0，与答错区分开。 */
  it('把未作答与答错分开显示', async () => {
    wrongBook.mockResolvedValue(book([blank(), multiple()]))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('String 对象不可修改'))

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('未作答')
    // 答错的那一行显示学生当时选的选项，而不是「未作答」。
    expect(rows[1]!.text()).toContain('A. ArrayList')
  })

  /** 解析默认收起，点开才显示标准答案、解析和教师评语。 */
  it('展开后才显示参考答案与教师评语', async () => {
    wrongBook.mockResolvedValue(book([subjective()]))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('简述封装的作用'))
    expect(wrapper.text()).not.toContain('缺少异常处理')

    await button(wrapper, '解析').trigger('click')

    expect(wrapper.text()).toContain('参考答案')
    expect(wrapper.text()).toContain('隐藏实现细节并只暴露必要接口')
    expect(wrapper.text()).toContain('缺少异常处理')
  })

  /** 没有可重练的客观题时禁用按钮并解释原因，而不是点了才报错。 */
  it('没有客观错题时禁用重练按钮', async () => {
    wrongBook.mockResolvedValue(book([subjective()]))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('简述封装的作用'))

    expect(button(wrapper, '开始重练').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('主观题请对照下方参考答案复习')
  })

  /**
   * 重练全流程：取题 → 界面上没有答案 → 作答 → 提交 → 结果里才出现标准答案。
   *
   * 中间那一步是这个用例的重点：练习界面出现标准答案，重练就失去意义了。
   */
  it('重练时不显示答案，提交后才给出对错与参考答案', async () => {
    wrongBook.mockResolvedValue(book([multiple()]))
    practiceSet.mockResolvedValue(set())
    submitPractice.mockResolvedValue(result(true))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('下列哪些属于 List'))

    await button(wrapper, '开始重练').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('错题重练'))

    // 默认只练没练对的题。
    expect(practiceSet.mock.lastCall![1]).toMatchObject({ onlyUnmastered: true })
    // 练习界面上不能出现任何形式的答案或解析。
    expect(wrapper.text()).not.toContain('参考答案')
    expect(wrapper.text()).not.toContain('多选题答案集合完全一致才得分')

    // 多选题按 checkbox 渲染，勾两个选项后提交。
    const boxes = wrapper.findAll('input[type="checkbox"]')
    expect(boxes.length).toBe(3)
    await boxes[0]!.trigger('change')
    await boxes[1]!.trigger('change')
    await button(wrapper, '提交并查看结果').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('本次重练结果'))

    expect(submitPractice.mock.lastCall![1]).toEqual([{ paperQuestionId: 21, answerContent: ['A', 'B'] }])
    const text = wrapper.text()
    expect(text).toContain('答对')
    expect(text).toContain('100')
    expect(text).toContain('多选题答案集合完全一致才得分')
    expect(text).toContain('本次练习不计入成绩')
    // 练完立刻刷新错题本，练习次数与「已掌握」标记才会更新。
    expect(wrongBook).toHaveBeenCalledTimes(2)
  })

  /** 一道题都没答就提交时，作答内容传 null，由后端判错——前端不自己判分。 */
  it('未作答的题目提交 null', async () => {
    wrongBook.mockResolvedValue(book([multiple()]))
    practiceSet.mockResolvedValue({
      size: 1,
      questions: [{
        paperQuestionId: 25, type: 'TRUE_FALSE', stem: 'String 对象不可修改。', options: null,
        maxScore: 10, examName: 'Java 在线考试', practiceCount: 1,
      }],
    })
    submitPractice.mockResolvedValue(result(false))
    const wrapper = mount(WrongQuestionBook)
    await vi.waitFor(() => expect(wrapper.text()).toContain('下列哪些属于 List'))

    await button(wrapper, '开始重练').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('错题重练'))
    await button(wrapper, '提交并查看结果').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('本次重练结果'))

    expect(submitPractice.mock.lastCall![1]).toEqual([{ paperQuestionId: 25, answerContent: null }])
    expect(wrapper.text()).toContain('答错')
  })

  /** 取练习题失败（都练对了）时只显示错误，仍留在错题清单上。 */
  it('没有需要重练的题时只显示错误', async () => {
    wrongBook.mockResolvedValue(book([multiple({ mastered: true, lastCorrect: true, practiceCount: 2 })]))
    practiceSet.mockImplementation(() => {
      throw new Error('没有需要重练的客观题了')
    })
    const wrapper = mount(WrongQuestionBook)
    // 等的必须是只有数据到达后才出现的内容：「已掌握」同时也是统计卡上的静态标签，
    // 等它会在数据还没回来时就通过，那一刻按钮仍因加载中而禁用，点击不会触发任何请求。
    await vi.waitFor(() => expect(wrapper.text()).toContain('下列哪些属于 List'))

    await button(wrapper, '开始重练').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('没有需要重练的客观题了'))

    expect(wrapper.text()).toContain('错题概况')
  })
})
