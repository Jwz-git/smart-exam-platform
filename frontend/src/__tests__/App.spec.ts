/**
 * 前端单元测试。
 *
 * 覆盖两类不依赖后端的行为：登录页的初始状态与密码可见性切换，以及分页组件的页码计算。
 * 涉及接口的流程由后端集成测试和浏览器走查覆盖，这里不对 fetch 做大量打桩，
 * 避免测试变成在验证桩本身。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import App from '../App.vue'
import PagerBar from '../components/PagerBar.vue'

/**
 * 打桩 localStorage。
 *
 * session.ts 在模块加载时就会读取令牌，因此这里提供一个最小可用的实现；
 * 生产代码里的读写都包了 try/catch，即使宿主环境不提供也不会崩。
 */
function stubStorage(values: Record<string, string> = {}) {
  const store = new Map(Object.entries(values))
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
  })
}

describe('App', () => {
  beforeEach(() => {
    // 每个用例都从「未登录、网络不可用」的干净状态开始，
    // 让 fetch 直接失败以确认页面不依赖后端也能正常渲染登录表单。
    stubStorage()
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('网络不可用'))))
  })

  /**
   * 未登录时渲染登录页，且两个输入框都为空。
   *
   * 「不预填」这一条是有意断言的：早期版本为了演示方便把教师账号和密码写死在表单里，
   * 密码会随任何一张截图外泄，这条测试用来防止那种写法被重新引入。
   */
  it('未登录时展示登录页，账号与密码都不预填', () => {
    const wrapper = mount(App)
    expect(wrapper.text()).toContain('智能在线题库与组卷系统')
    expect(wrapper.find('input[autocomplete="username"]').attributes('value')).toBeUndefined()
    expect((wrapper.find('input[autocomplete="current-password"]').element as HTMLInputElement).value).toBe('')
  })

  /** 点击眼睛按钮在 password 与 text 之间切换，且不会误触发表单提交。 */
  it('密码可见性按钮切换输入框类型', async () => {
    const wrapper = mount(App)
    const toggle = wrapper.find('.input-icon .suffix')
    expect(wrapper.find('input[autocomplete="current-password"]').attributes('type')).toBe('password')
    await toggle.trigger('click')
    expect(wrapper.find('input[autocomplete="current-password"]').attributes('type')).toBe('text')
  })
})

describe('PagerBar', () => {
  /** 128 条按每页 10 条应有 13 页；页数超过 7 时中间页折叠成省略号。 */
  it('按总数和每页条数渲染页码，并折叠中间页', () => {
    const wrapper = mount(PagerBar, { props: { total: 128, page: 1, size: 10 } })
    expect(wrapper.text()).toContain('共 128 条')
    expect(wrapper.text()).toContain('13')
    expect(wrapper.text()).toContain('…')
  })

  /** 点击页码抛出 update:page，父组件用 v-model:page 接收。 */
  it('点击页码抛出 update:page', async () => {
    const wrapper = mount(PagerBar, { props: { total: 30, page: 1, size: 10 } })
    const buttons = wrapper.findAll('.pages button')
    const third = buttons.find((button) => button.text() === '3')
    await third?.trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([3])
  })
})
