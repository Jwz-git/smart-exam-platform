import { describe, it, expect } from 'vitest'

import { mount } from '@vue/test-utils'
import App from '../App.vue'

describe('App', () => {
  it('renders the project skeleton', () => {
    const wrapper = mount(App)
    expect(wrapper.text()).toContain('在线题库与组卷系统')
    expect(wrapper.text()).toContain('Vue 3 + TypeScript + Vite')
  })
})
