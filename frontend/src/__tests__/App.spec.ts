import { describe, it, expect, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import App from '../App.vue'

describe('App', () => {
  it('renders the login form', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn<(key: string) => string | null>(() => null),
      setItem: vi.fn<(key: string, value: string) => void>(),
      removeItem: vi.fn<(key: string) => void>(),
    })
    const wrapper = mount(App)
    expect(wrapper.text()).toContain('登录在线题库')
    expect(wrapper.text()).toContain('演示教师')
  })
})
