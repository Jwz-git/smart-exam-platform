/**
 * 试卷导出弹窗的前端单元测试。
 *
 * 守两件事：
 * 1. **「含答案」这个开关必须准确。** 一份本该发给学生的卷子如果带着答案，问题已经发生了。
 *    因此这里断言开关状态与请求参数一一对应，且改开关会重新取内容；
 * 2. **CSV 必然含答案，开关要被锁住并解释原因**，而不是让它看起来生效了却没有用。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PaperExportModal from '../views/PaperExportModal.vue'
import type { Paper } from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: { ...actual.api, exportPaper: vi.fn<typeof actual.api.exportPaper>() },
  }
})
const { api } = await import('../api')
const exportPaper = vi.mocked(api.exportPaper)

const paper: Paper = {
  id: 7, name: '期中 Java 基础测试卷', durationMinutes: 45, totalScore: 25, status: 'PUBLISHED', questions: [],
}

function open() {
  return mount(PaperExportModal, { props: { paper } })
}

/** 每个用例都从零次调用开始，否则「取了几次内容」这类断言会把上一个用例的调用算进来。 */
beforeEach(() => {
  exportPaper.mockReset()
})

/** 等预览文本真正渲染出来：接口被调用只说明请求发出了，await 链还没走完。 */
async function waitForContent(wrapper: ReturnType<typeof mount>, expected: string) {
  await vi.waitFor(() =>
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toContain(expected))
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text().startsWith(text))
  if (!found) throw new Error(`找不到按钮：${text}`)
  return found
}

describe('PaperExportModal', () => {
  /** 打开就取一次预览，默认是「Markdown、不含答案」——发给学生的那一份。 */
  it('打开时默认取不含答案的 Markdown', async () => {
    exportPaper.mockResolvedValue('# 期中 Java 基础测试卷\n')
    const wrapper = open()
    await waitForContent(wrapper, '期中 Java 基础测试卷')

    expect(exportPaper.mock.lastCall!.slice(1)).toEqual([7, 'md', false])
    expect(wrapper.text()).toContain('可直接发给学生')
  })

  /** 勾上「含答案」立刻重新取一次，参数变成 withAnswers=true。 */
  it('勾选含答案后重新取带答案的正文', async () => {
    exportPaper.mockResolvedValue('# 卷子\n')
    const wrapper = open()
    await waitForContent(wrapper, '卷子')

    exportPaper.mockResolvedValue('# 卷子\n## 参考答案与解析\n1. 答案：A\n')
    await wrapper.find('input[type="checkbox"]').setValue(true)
    await waitForContent(wrapper, '参考答案')

    expect(exportPaper).toHaveBeenCalledTimes(2)
    expect(exportPaper.mock.lastCall!.slice(1)).toEqual([7, 'md', true])
  })

  /** 切到 CSV：开关被锁死为「含答案」，并说明原因（导入的必填列）。 */
  it('切到 CSV 后锁定含答案开关并说明原因', async () => {
    exportPaper.mockResolvedValue('# 卷子\n')
    const wrapper = open()
    await vi.waitFor(() => expect(exportPaper).toHaveBeenCalledTimes(1))

    exportPaper.mockResolvedValue('﻿题型,题干,难度,知识点,分值,选项,标准答案,解析,标签\n')
    await wrapper.find('select').setValue('csv')
    await vi.waitFor(() => expect(exportPaper).toHaveBeenCalledTimes(2))

    expect(exportPaper.mock.lastCall!.slice(1)).toEqual([7, 'csv', false])
    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.attributes('disabled')).toBeDefined()
    expect((checkbox.element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('标准答案')
    expect(wrapper.text()).toContain('回导题库')
  })

  /** 下载用的是已经取回的文本，不再请求一次：保证下载到的与刚才看到的完全一致。 */
  it('下载用当前预览的内容而不再请求一次', async () => {
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:mock')
    const revokeObjectURL = vi.fn<(url: string) => void>()
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, configurable: true })
    // jsdom 里点击 blob 链接会告警「未实现的导航」，因此把 click 换成空实现。
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    exportPaper.mockResolvedValue('# 卷子\n')

    const wrapper = open()
    await waitForContent(wrapper, '卷子')
    await button(wrapper, '下载文件').trigger('click')

    expect(click).toHaveBeenCalled()
    expect(createObjectURL).toHaveBeenCalled()
    expect(exportPaper).toHaveBeenCalledTimes(1)
    click.mockRestore()
  })

  /** 取不到内容时禁用下载按钮，避免下载出一个空文件。 */
  it('取内容失败时禁用下载', async () => {
    exportPaper.mockImplementation(() => {
      throw new Error('不能访问其他教师的试卷')
    })
    const wrapper = open()
    await vi.waitFor(() => expect(wrapper.text()).toContain('不能访问其他教师的试卷'))

    expect(button(wrapper, '下载文件').attributes('disabled')).toBeDefined()
  })
})
