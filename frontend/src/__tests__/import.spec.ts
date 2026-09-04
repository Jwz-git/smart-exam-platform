/**
 * 题库批量导入的前端单元测试。
 *
 * 守的是这个功能唯一的安全承诺：**没预览过不给导入，预览不写库，预览的数字就是实际的数字**。
 * 批量操作一旦出错没法一键撤销，因此「先看清楚再落库」这条流程不能被绕过——
 * 下面第一、二条用例正是钉住这一点。
 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import QuestionImportModal from '../views/QuestionImportModal.vue'
import type { ImportResult } from '../api'

// 只替换两个导入相关的方法，其余导出（typeLabels 等）保持真实实现。
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      importQuestions: vi.fn<typeof actual.api.importQuestions>(),
      importTemplate: vi.fn<typeof actual.api.importTemplate>(),
    },
  }
})
const { api } = await import('../api')
const importQuestions = vi.mocked(api.importQuestions)
const importTemplate = vi.mocked(api.importTemplate)

/** 等一个宏任务，让组件内部的 await 链走完再断言。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 一份典型的预览结果：两行可导入、一行跳过、一行失败。 */
function result(overrides: Partial<ImportResult> = {}): ImportResult {
  return {
    dryRun: true,
    total: 4,
    imported: 2,
    skipped: 1,
    failed: 1,
    rows: [
      { line: 2, outcome: 'IMPORTED', stem: '题干甲', type: 'SINGLE_CHOICE', questionId: null, message: null },
      { line: 3, outcome: 'IMPORTED', stem: '题干乙', type: 'TRUE_FALSE', questionId: null, message: null },
      { line: 4, outcome: 'SKIPPED', stem: '题干丙', type: 'SHORT_ANSWER', questionId: null,
        message: '题库中已存在相同题干的题目，本行跳过' },
      { line: 5, outcome: 'FAILED', stem: '题干丁', type: null, questionId: null,
        message: '无法识别的题型「火箭题」，只能填 单选题 / 多选题 / 判断题 / 简答题 / 编程题' },
    ],
    ...overrides,
  }
}

/** 挂载弹窗并填入表格内容，返回 wrapper。 */
async function open(content = '题型,题干,知识点,标准答案\n简答题,题干甲,Java,参考答案\n') {
  const wrapper = mount(QuestionImportModal)
  await wrapper.find('textarea').setValue(content)
  return wrapper
}

/** 按钮文字取按钮，避免依赖 DOM 顺序。 */
function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text().startsWith(text))
  if (!found) throw new Error(`找不到按钮：${text}`)
  return found
}

describe('QuestionImportModal', () => {
  /**
   * 预览用 dryRun=true 调用，逐行结果和四个计数都如实渲染，跳过与失败分开显示。
   *
   * 「跳过」不能混进失败数：它通常意味着同一个文件导了两次，是最常见的误操作，
   * 显示成失败会让教师去找一个并不存在的格式错误。
   */
  it('预览时传 dryRun 并逐行展示结果', async () => {
    importQuestions.mockResolvedValue(result())
    const wrapper = await open()

    await button(wrapper, '预览').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('table').exists()).toBe(true))

    expect(importQuestions.mock.lastCall![1]).toMatchObject({ dryRun: true })
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(4)
    // 行号是原文行号，教师照着它去 Excel 里定位。
    const failedRow = rows[3]!
    expect(failedRow.text()).toContain('5')
    expect(failedRow.text()).toContain('无法识别的题型')
    // 预览阶段说的是「可导入」而不是「已导入」，两者差一个字但含义完全不同。
    expect(wrapper.text()).toContain('可导入')
    expect(wrapper.text()).not.toContain('已导入')
    expect(wrapper.text()).toContain('跳过')
  })

  /**
   * 没预览过不给导入；预览之后才允许，且导入用 dryRun=false 并抛出 imported 事件。
   *
   * 这条是流程上的硬约束：确认按钮的禁用状态就是「先看清楚再落库」的实现。
   */
  it('预览前禁用导入按钮，预览后才允许写库', async () => {
    importQuestions.mockResolvedValue(result())
    const wrapper = await open()

    expect(button(wrapper, '确认导入').attributes('disabled')).toBeDefined()

    await button(wrapper, '预览').trigger('click')
    await vi.waitFor(() => expect(button(wrapper, '确认导入').attributes('disabled')).toBeUndefined())
    // 按钮上带出可导入的道数，点之前就知道会写入多少。
    expect(button(wrapper, '确认导入').text()).toContain('2')

    importQuestions.mockResolvedValue(result({ dryRun: false, imported: 2 }))
    await button(wrapper, '确认导入').trigger('click')
    await vi.waitFor(() => expect(wrapper.emitted('imported')).toBeTruthy())

    expect(importQuestions.mock.lastCall![1]).toMatchObject({ dryRun: false })
    expect(wrapper.emitted('imported')?.[0]?.[0]).toBe(2)
    expect(wrapper.text()).toContain('导入完成')
    // 导入过一次之后不能再点，避免同一批内容重复提交。
    expect(button(wrapper, '确认导入').attributes('disabled')).toBeDefined()
  })

  /**
   * 改动表格内容会作废上一次的预览。
   *
   * 否则会出现「预览的是旧内容、导入的是新内容」——预览结果就成了误导。
   */
  it('内容变化后作废预览结果', async () => {
    importQuestions.mockResolvedValue(result())
    const wrapper = await open()
    await button(wrapper, '预览').trigger('click')
    await vi.waitFor(() => expect(button(wrapper, '确认导入').attributes('disabled')).toBeUndefined())

    await wrapper.find('textarea').setValue('题型,题干,知识点,标准答案\n判断题,新题干,Java,正确\n')

    expect(button(wrapper, '确认导入').attributes('disabled')).toBeDefined()
    expect(wrapper.find('table').exists()).toBe(false)
  })

  /** 选文件与手工粘贴共用同一条路径：文件只是把文本填进同一个输入框。 */
  it('选文件后把内容填进表格输入框', async () => {
    const wrapper = mount(QuestionImportModal)
    const csv = '题型,题干,知识点,标准答案\n判断题,来自文件的题干,Java,正确\n'
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File([csv], 'questions.csv', { type: 'text/csv' })],
    })

    await input.trigger('change')
    await flush()
    await wrapper.vm.$nextTick()

    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toContain('来自文件的题干')
    expect(wrapper.text()).toContain('questions.csv')
  })

  /** 模板从后端取而不是前端写死，保证表头与解析器不会各自漂移。 */
  it('下载模板走后端接口', async () => {
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:mock')
    const revokeObjectURL = vi.fn<(url: string) => void>()
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, configurable: true })
    // jsdom 里点击带 blob 链接的 <a> 会触发「未实现的导航」告警，因此把 click 换成空实现。
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    importTemplate.mockResolvedValue('题型,题干,难度,知识点,分值,选项,标准答案,解析,标签\n')

    const wrapper = mount(QuestionImportModal)
    await button(wrapper, '下载模板').trigger('click')
    await vi.waitFor(() => expect(click).toHaveBeenCalled())

    expect(importTemplate).toHaveBeenCalled()
    expect(createObjectURL).toHaveBeenCalled()
    click.mockRestore()
  })

  /** 整表级失败（缺列、超行数）只显示错误，不渲染任何结果表。 */
  it('接口失败时只显示错误且不显示结果表', async () => {
    // 用同步 throw 而不是被拒绝的 Promise：组件里的 await 对两者处理相同，
    // 而被拒绝的 Promise 经 mock 记录后会多出一个无人接管的派生 Promise，
    // 用例随即被判成「未处理的拒绝」。
    importQuestions.mockImplementation(() => {
      throw new Error('表头缺少必填列：题型、标准答案。可先下载模板，按模板的表头填写')
    })
    const wrapper = await open()

    await button(wrapper, '预览').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('表头缺少必填列')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(button(wrapper, '确认导入').attributes('disabled')).toBeDefined()
  })
})
