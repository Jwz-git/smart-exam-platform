/**
 * 规则自动组卷弹窗的前端单元测试。
 *
 * 守三件事：
 * 1. 「不限」必须转成 null 而不是空字符串——空串会被后端当成非法枚举值直接 400；
 * 2. 方案里的题目和分值要原样交给组卷页，因为保存走的是原来那条 createPaper 路径，
 *    这里传丢一个分值，保存时就会因为「分值合计不等于总分」被拒；
 * 3. 候选池等于抽题数时要提示「无可换」——否则教师会一直点「换一批」却发现题目没变。
 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AutoComposeModal from '../views/AutoComposeModal.vue'
import type { AutoComposePlan, Question } from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: { ...actual.api, autoComposePaper: vi.fn<typeof actual.api.autoComposePaper>() },
  }
})
const { api } = await import('../api')
const autoComposePaper = vi.mocked(api.autoComposePaper)

/** 造一道题。 */
function question(id: number, stem: string): Question {
  return {
    id, type: 'SINGLE_CHOICE', stem, difficulty: 'EASY', tags: null, standardAnswer: ['A'],
    explanation: null, suggestedScore: 10, knowledgePointId: 1, knowledgePointName: 'Java 基础',
    status: 'ACTIVE', options: [{ key: 'A', content: '甲' }, { key: 'B', content: '乙' }],
  }
}

/** 造一份方案：两道题、总分 30、第一条规则的候选池刚好等于抽题数。 */
function plan(): AutoComposePlan {
  return {
    questionCount: 2,
    totalScore: 30,
    rules: [
      { ruleIndex: 1, label: '单选题 / 易 / Java 基础', count: 1, poolSize: 1, score: 20, subtotal: 20 },
      { ruleIndex: 2, label: '全部题目', count: 1, poolSize: 7, score: 10, subtotal: 10 },
    ],
    items: [
      { ruleIndex: 1, score: 20, question: question(11, '下列哪一项是基本类型？') },
      { ruleIndex: 2, score: 10, question: question(12, '下列哪一项不是关键字？') },
    ],
  }
}

const points = [{ id: 1, name: 'Java 基础' }, { id: 2, name: '集合' }]

function open() {
  return mount(AutoComposeModal, { props: { points } })
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text().startsWith(text))
  if (!found) throw new Error(`找不到按钮：${text}`)
  return found
}

describe('AutoComposeModal', () => {
  /** 默认那一条规则三个维度都是「不限」，提交时必须是三个 null。 */
  it('把「不限」提交为 null 而不是空字符串', async () => {
    autoComposePaper.mockResolvedValue(plan())
    const wrapper = open()

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(autoComposePaper).toHaveBeenCalled())

    expect(autoComposePaper.mock.lastCall![1]).toEqual([
      { type: null, difficulty: null, knowledgePointId: null, count: 5, score: 10 },
    ])
  })

  /** 填了筛选条件就按条件提交，数字字段要是 number 而不是字符串。 */
  it('按选择的条件提交规则', async () => {
    autoComposePaper.mockResolvedValue(plan())
    const wrapper = open()
    const selects = wrapper.findAll('tbody select')
    await selects[0]!.setValue('MULTIPLE_CHOICE')
    await selects[1]!.setValue('HARD')
    await selects[2]!.setValue('2')
    const inputs = wrapper.findAll('tbody input[type="number"]')
    await inputs[0]!.setValue('3')
    await inputs[1]!.setValue('5.5')

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(autoComposePaper).toHaveBeenCalled())

    expect(autoComposePaper.mock.lastCall![1]).toEqual([
      { type: 'MULTIPLE_CHOICE', difficulty: 'HARD', knowledgePointId: 2, count: 3, score: 5.5 },
    ])
  })

  /** 加一条规则后提交两条；至少保留一条，删不掉最后一条。 */
  it('支持增删规则且至少保留一条', async () => {
    autoComposePaper.mockResolvedValue(plan())
    const wrapper = open()
    expect(wrapper.findAll('tbody tr').length).toBe(1)
    expect(button(wrapper, '删除').attributes('disabled')).toBeDefined()

    await button(wrapper, '添加规则').trigger('click')
    expect(wrapper.findAll('tbody tr').length).toBe(2)

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(autoComposePaper).toHaveBeenCalled())
    expect(autoComposePaper.mock.lastCall![1].length).toBe(2)

    await button(wrapper, '删除').trigger('click')
    expect(wrapper.findAll('tbody tr').length).toBe(1)
  })

  /** 方案渲染：总分、逐条规则、抽中的题目，以及「候选池 = 抽题数」时的无可换提示。 */
  it('渲染方案并提示候选池没有余量', async () => {
    autoComposePaper.mockResolvedValue(plan())
    const wrapper = open()

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('抽中的题目'))

    const text = wrapper.text()
    expect(text).toContain('单选题 / 易 / Java 基础')
    expect(text).toContain('无可换')
    expect(text).toContain('下列哪一项是基本类型？')
    expect(text).toContain('30')
    // 生成过一次之后按钮改成「换一批」，明确表达再点会得到另一组题。
    expect(button(wrapper, '换一批').exists()).toBe(true)
  })

  /** 应用方案只抛事件，由组卷页去填表和保存——自动组卷本身不写库。 */
  it('应用方案时把题目与分值原样抛给组卷页', async () => {
    autoComposePaper.mockResolvedValue(plan())
    const wrapper = open()
    expect(button(wrapper, '应用到试卷').attributes('disabled')).toBeDefined()

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(button(wrapper, '应用到试卷').attributes('disabled')).toBeUndefined())
    await button(wrapper, '应用到试卷').trigger('click')

    const applied = wrapper.emitted('apply')?.[0]?.[0] as { question: Question; score: number }[]
    expect(applied.length).toBe(2)
    expect(applied[0]).toMatchObject({ score: 20 })
    expect(applied[0]!.question.id).toBe(11)
    expect(applied[1]).toMatchObject({ score: 10 })
  })

  /** 抽题失败（题目不够）只显示错误，不渲染方案，也不允许应用。 */
  it('抽题失败时只显示错误', async () => {
    autoComposePaper.mockImplementation(() => {
      throw new Error('第 1 条规则（单选题 / 易）需要 5 道题，题库当前只有 3 道可用')
    })
    const wrapper = open()

    await button(wrapper, '生成方案').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('只有 3 道可用'))

    expect(wrapper.text()).not.toContain('抽中的题目')
    expect(button(wrapper, '应用到试卷').attributes('disabled')).toBeDefined()
  })
})
