/**
 * 可编辑系统设置的前端单元测试。
 *
 * 这一页原先是纯只读的，开放编辑后有三件事必须守住：
 * 1. **只有管理员能改。** 教师看到的是同一页数据，但没有输入框和保存按钮；
 * 2. **只提交真正改动的键。** 全量提交会让「已更新 N 项」这个数字失去意义，
 *    也会把没碰过的项写成「已覆盖」；
 * 3. **恢复默认传空串而不是默认值。** 前者删掉覆盖行、以后跟着环境变量走，
 *    后者会让这一项永远停在今天的默认值上。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { SettingItem, SystemSettings } from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      systemSettings: vi.fn<typeof actual.api.systemSettings>(),
      updateSettings: vi.fn<typeof actual.api.updateSettings>(),
    },
  }
})
const { api } = await import('../api')
const { user } = await import('../session')
const SystemSettingsView = (await import('../views/SystemSettingsView.vue')).default
const systemSettings = vi.mocked(api.systemSettings)
const updateSettings = vi.mocked(api.updateSettings)

/** 造一个可编辑项，默认是「及格线 60%，未被覆盖」。 */
function item(overrides: Partial<SettingItem> = {}): SettingItem {
  return {
    key: 'exam.pass-ratio-percent', label: '及格线', group: '考试与评分', type: 'DECIMAL',
    value: '60', defaultValue: '60', overridden: false, min: 0, max: 100, unit: '%',
    description: '及格分数线 = 试卷总分 × 该百分比。', updatedAt: null, updatedBy: null,
    ...overrides,
  }
}

/** 布尔项：导入是否跳过重复题干。 */
function toggle(overrides: Partial<SettingItem> = {}): SettingItem {
  return item({
    key: 'question.import-skip-duplicate-stem', label: '导入跳过重复题干', group: '题库', type: 'BOOLEAN',
    value: 'true', defaultValue: 'true', min: null, max: null, unit: '',
    description: '开启时题干重复的行只跳过、不写库。', ...overrides,
  })
}

function settings(editable: SettingItem[]): SystemSettings {
  return {
    editable,
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
    ai: {
      configured: true, protocol: 'openai', baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-chat', timeoutSeconds: 60, maxTokens: 2048,
    },
    database: { product: 'MySQL', version: '8.4.0', schemaVersion: '5', sessionTimeZone: 'UTC（连接串强制会话时区）' },
  }
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((element) => element.text().startsWith(text))
  if (!found) throw new Error(`找不到按钮：${text}`)
  return found
}

beforeEach(() => {
  systemSettings.mockReset()
  updateSettings.mockReset()
  user.value = { id: 1, username: 'admin', displayName: '系统管理员', role: 'ADMIN' }
})

describe('SystemSettingsView 可编辑设置', () => {
  /** 管理员看到输入框、来源标记和默认值；未改动时保存按钮禁用。 */
  it('管理员可见输入框，未改动时不能保存', async () => {
    systemSettings.mockResolvedValue(settings([item(), toggle()]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('可修改的运行参数'))

    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('默认值')
    expect(wrapper.text()).toContain('默认 60%')
    expect(button(wrapper, '保存改动').attributes('disabled')).toBeDefined()
    // 没被覆盖的项不能「恢复默认」——本来就是默认值。
    expect(button(wrapper, '恢复默认').attributes('disabled')).toBeDefined()
  })

  /** 教师看到同一页数据，但没有输入框、保存和恢复默认。 */
  it('教师只读，不渲染任何编辑入口', async () => {
    user.value = { id: 2, username: 'teacher', displayName: '张老师', role: 'TEACHER' }
    systemSettings.mockResolvedValue(settings([item(), toggle()]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('可修改的运行参数'))

    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((element) => element.text().startsWith('保存改动'))).toBe(false)
    expect(wrapper.text()).toContain('只有管理员可以修改')
    // 值本身仍然要显示出来：教师需要知道当前及格线是多少。
    expect(wrapper.text()).toContain('60%')
  })

  /** 改一项、保存：只提交这一项，并用返回的视图刷新页面。 */
  it('只提交改动过的键并显示更新条数', async () => {
    systemSettings.mockResolvedValue(settings([item(), toggle()]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('可修改的运行参数'))

    await wrapper.find('input[type="number"]').setValue('50')
    expect(button(wrapper, '保存改动').text()).toContain('1')

    updateSettings.mockResolvedValue({
      changed: 1,
      settings: settings([
        item({ value: '50', overridden: true, updatedAt: '2026-09-04T03:00:00Z', updatedBy: '系统管理员' }),
        toggle(),
      ]),
    })
    await button(wrapper, '保存改动').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('已更新 1 项设置'))

    // 布尔项没碰过，因此不能出现在请求体里。
    expect(updateSettings.mock.lastCall![1]).toEqual({ 'exam.pass-ratio-percent': '50' })
    expect(wrapper.text()).toContain('已覆盖')
    expect(wrapper.text()).toContain('系统管理员')
    // 保存后草稿与当前值一致，保存按钮重新禁用。
    expect(button(wrapper, '保存改动').attributes('disabled')).toBeDefined()
  })

  /** 恢复默认传空串——它的含义是删掉覆盖行，而不是写回一个当时的默认值。 */
  it('恢复默认提交空串', async () => {
    systemSettings.mockResolvedValue(settings([
      item({ value: '50', overridden: true, updatedBy: '系统管理员', updatedAt: '2026-09-04T03:00:00Z' }),
    ]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('已覆盖'))

    updateSettings.mockResolvedValue({ changed: 1, settings: settings([item()]) })
    await button(wrapper, '恢复默认').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('已恢复默认值'))

    expect(updateSettings.mock.lastCall![1]).toEqual({ 'exam.pass-ratio-percent': '' })
    expect(wrapper.text()).toContain('默认值')
  })

  /** 放弃改动把草稿退回当前生效值，不发任何请求。 */
  it('放弃改动不调用接口', async () => {
    systemSettings.mockResolvedValue(settings([item()]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('可修改的运行参数'))

    await wrapper.find('input[type="number"]').setValue('80')
    await button(wrapper, '放弃改动').trigger('click')

    expect((wrapper.find('input[type="number"]').element as HTMLInputElement).value).toBe('60')
    expect(updateSettings).not.toHaveBeenCalled()
  })

  /** 后端拒绝时显示可读原因，且页面上的值不变——不能显示成已经改成功了。 */
  it('保存失败时保留原值并显示错误', async () => {
    systemSettings.mockResolvedValue(settings([item()]))
    const wrapper = mount(SystemSettingsView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('可修改的运行参数'))

    await wrapper.find('input[type="number"]').setValue('120')
    updateSettings.mockImplementation(() => {
      throw new Error('「及格线」取值范围是 0—100%')
    })
    await button(wrapper, '保存改动').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('取值范围是 0—100%'))

    expect(wrapper.text()).toContain('默认值')
    expect(wrapper.text()).not.toContain('已更新')
  })
})
