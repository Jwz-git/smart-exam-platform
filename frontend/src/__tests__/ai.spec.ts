/**
 * AI 辅助出题的前端单元测试。
 *
 * 关注三件事：生成结果如何渲染、被丢弃的草稿是否如实展示、以及「采用草稿」是否真的走
 * 普通的新增流程。最后一条是这个功能的安全边界——草稿必须经过题目表单和后端校验才能入库，
 * 前端不能有任何「直接保存 AI 结果」的捷径。
 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AiDraftModal from '../views/AiDraftModal.vue'
import QuestionFormModal from '../views/QuestionFormModal.vue'
import type { AiDraftResult, KnowledgePoint, QuestionPayload } from '../api'

// 只替换 generateDrafts，其余导出（typeLabels、isChoice 等）保持真实实现。
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: { ...actual.api, generateDrafts: vi.fn<typeof actual.api.generateDrafts>() } }
})
const { api } = await import('../api')
const generateDrafts = vi.mocked(api.generateDrafts)

const points: KnowledgePoint[] = [{ id: 3, name: '面向对象' }]

/**
 * 等一个宏任务，让组件内部的 await 链走完再断言。
 *
 * 失败路径必须用它而不是 `vi.waitFor`：`waitFor` 可能在拒绝还挂着的时候就通过，
 * 用例随即结束，那个拒绝就会以「未处理的拒绝」的身份把测试判失败。
 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 造一份合规的单选题草稿。 */
function draft(overrides: Partial<QuestionPayload> = {}): QuestionPayload {
  return {
    type: 'SINGLE_CHOICE',
    stem: '封装的主要作用是什么？',
    difficulty: 'MEDIUM',
    tags: 'Java, 面向对象',
    standardAnswer: ['A'],
    explanation: '封装隐藏实现细节。',
    suggestedScore: 10,
    knowledgePointId: 3,
    options: [{ key: 'A', content: '隐藏内部实现' }, { key: 'B', content: '提高运行速度' }],
    ...overrides,
  }
}

/** 造一份生成结果。 */
function result(overrides: Partial<AiDraftResult> = {}): AiDraftResult {
  return { protocol: 'openai', model: 'deepseek-chat', drafts: [draft()], warnings: [], ...overrides }
}

describe('AiDraftModal', () => {
  // 每个用例都自己设定 mock 行为并只调用一次，因此不需要在钩子里重置。
  /** 生成成功后展示草稿、协议和模型名，并把被丢弃的草稿原因一并显示。 */
  it('渲染生成的草稿与被丢弃的原因', async () => {
    generateDrafts.mockResolvedValue(result({ warnings: ['第 2 道草稿未通过校验：选择题至少需要两个选项'] }))
    const wrapper = mount(AiDraftModal, { props: { points } })

    await wrapper.find('.btn-primary').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('封装的主要作用是什么？'))

    expect(wrapper.text()).toContain('deepseek-chat')
    expect(wrapper.text()).toContain('OpenAI 兼容')
    expect(wrapper.text()).toContain('A. 隐藏内部实现')
    // 少给的那一道必须说明原因，不能静默丢弃。
    expect(wrapper.text()).toContain('第 2 道草稿未通过校验')
  })

  /** 生成参数取自表单：题型、难度、知识点和分值由教师指定，不由模型决定。 */
  it('把教师选定的题型难度分值传给后端', async () => {
    generateDrafts.mockResolvedValue(result())
    const wrapper = mount(AiDraftModal, { props: { points } })

    await wrapper.find('.btn-primary').trigger('click')
    await vi.waitFor(() => expect(generateDrafts).toHaveBeenCalled())

    expect(generateDrafts.mock.lastCall![1]).toMatchObject({
      knowledgePointId: 3, type: 'SINGLE_CHOICE', difficulty: 'MEDIUM', count: 1, suggestedScore: 10,
    })
  })

  /** 点「编辑并保存」只抛出 adopt 事件，弹窗本身不保存任何东西。 */
  it('采用草稿时抛出 adopt 事件而不直接保存', async () => {
    generateDrafts.mockResolvedValue(result())
    const wrapper = mount(AiDraftModal, { props: { points } })
    await wrapper.find('.btn-primary').trigger('click')
    await vi.waitFor(() => expect(wrapper.findAll('.answer-card').length).toBe(1))

    await wrapper.find('.answer-card .btn-primary').trigger('click')

    expect(wrapper.emitted('adopt')?.[0]?.[0]).toMatchObject({ stem: '封装的主要作用是什么？' })
  })

  /** 生成失败时只显示错误，不渲染任何草稿——手工出题不受影响。 */
  it('生成失败时显示错误且不展示草稿', async () => {
    // 用同步 throw 而不是被拒绝的 Promise：组件里的 `await` 对两者的处理完全一样，
    // 都会进同一个 catch 分支；而被拒绝的 Promise 经过 Vitest 的 mock 记录后会多出一个
    // 没人接管的派生 Promise，用例随即被判成「未处理的拒绝」。
    generateDrafts.mockImplementation(() => {
      throw new Error('未配置 AI 密钥，无法生成草稿；手工出题不受影响')
    })
    const wrapper = mount(AiDraftModal, { props: { points } })

    await wrapper.find('.btn-primary').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('未配置 AI 密钥')
    expect(wrapper.findAll('.answer-card').length).toBe(0)
  })
})

describe('QuestionFormModal 预填 AI 草稿', () => {
  /**
   * 草稿预填进表单后，标题表明这是待确认的草稿，且保存时抛出的仍是普通的新增载荷。
   *
   * 这条测试守的是那道边界：AI 结果必须经过同一个表单和同一个新增接口，
   * 不存在「AI 专用保存」的路径。
   */
  it('按草稿回填表单并按新增流程提交', async () => {
    const wrapper = mount(QuestionFormModal, {
      props: { question: null, points, prefill: draft() },
    })

    expect(wrapper.text()).toContain('确认 AI 草稿')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('封装的主要作用是什么？')

    await wrapper.find('form').trigger('submit')

    const payload = wrapper.emitted('save')?.[0]?.[0] as QuestionPayload
    expect(payload).toMatchObject({
      type: 'SINGLE_CHOICE', stem: '封装的主要作用是什么？', knowledgePointId: 3, suggestedScore: 10,
    })
    expect(payload.standardAnswer).toEqual(['A'])
    expect(payload.options).toEqual([
      { key: 'A', content: '隐藏内部实现' }, { key: 'B', content: '提高运行速度' },
    ])
  })
})
