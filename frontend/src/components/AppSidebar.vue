<script setup lang="ts">
/** 左侧深色导航，菜单项按角色过滤；未实现的入口仍然显示，点击后由占位页说明。 */
/**
 * 菜单项。
 *
 * @property key   视图标识，与 App.vue 里的 active 对应
 * @property icon  图标名，对应下方 paths 表里的键
 * @property ready 是否已实现；false 的入口仍然显示，点击后由占位页说明，
 *                 这样界面能对上原型，同时不谎称功能已完成
 */
export interface MenuItem { key: string; label: string; icon: string; ready: boolean }

const props = defineProps<{ items: MenuItem[]; active: string }>()

/** 选中事件交给外层处理，侧栏本身不持有当前页状态。 */
const emit = defineEmits<{ select: [key: string] }>()

/** 24×24 线性图标的 path，风格对齐原型里的侧栏图标。 */
const paths: Record<string, string> = {
  home: 'M3 10.5 12 3l9 7.5V21H3z',
  bank: 'M4 6c0-1.5 3.6-2.5 8-2.5S20 4.5 20 6v12c0 1.5-3.6 2.5-8 2.5S4 19.5 4 18zM4 6c0 1.5 3.6 2.5 8 2.5S20 7.5 20 6M4 12c0 1.5 3.6 2.5 8 2.5s8-1 8-2.5',
  paper: 'M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6',
  exam: 'M8 4h8v3H8zM6 7h12v13H6zM9 12h6M9 16h4',
  grade: 'M5 20V10M10 20V4M15 20v-8M20 20V7',
  stats: 'M4 20h16M7 20v-7M12 20V6M17 20v-4',
  settings: 'M12 15.5A3.5 3.5 0 1 0 12 8.5a3.5 3.5 0 0 0 0 7M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2 2 2 0 1 1-4 0 1.7 1.7 0 0 0-2.9-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 3 15a2 2 0 1 1 0-4 1.7 1.7 0 0 0 1.4-2.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 10 4a2 2 0 1 1 4 0 1.7 1.7 0 0 0 2.9 1.4l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1A1.7 1.7 0 0 0 21 11a2 2 0 1 1 0 4 1.7 1.7 0 0 0-1.6 0',
  users: 'M16 20v-1.5a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4V20M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M21 20v-1.5a4 4 0 0 0-3-3.9M16.5 3.6a4 4 0 0 1 0 7.3',
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand"><span>智能在线题库与组卷系统</span></div>
    <nav>
      <!-- 用 button 而不是 a：这里不是真实链接跳转，button 天然支持键盘操作；
           aria-current 让读屏器播报当前所在页面 -->
      <button
        v-for="item in props.items"
        :key="item.key"
        type="button"
        :class="{ active: props.active === item.key }"
        :aria-current="props.active === item.key ? 'page' : undefined"
        @click="emit('select', item.key)"
      >
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path :d="paths[item.icon]" />
        </svg>
        <span>{{ item.label }}</span>
      </button>
    </nav>
  </aside>
</template>
