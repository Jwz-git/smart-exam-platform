/**
 * 前端入口。
 *
 * 只做两件事：引入全局样式、挂载根组件。刻意保持简单——
 * 路由、状态管理都没有引入，登录态放在 session.ts 的模块级 ref 里，
 * 视图切换由 App.vue 用一个字符串状态控制。
 *
 * theme.css 必须在 App.vue 之前引入，保证全局令牌先落地，
 * 组件内的 scoped 样式才能稳定覆盖到它。
 */
import { createApp } from 'vue'

import './theme.css'
import App from './App.vue'

createApp(App).mount('#app')
