<script setup lang="ts">
/**
 * 横向条形图。
 *
 * 刻意不引入图表库（ECharts、Chart.js 之类）：本页需要的只是「按最大值等比缩放的一条横杠」，
 * 用 CSS 宽度百分比就能画对，而引入图表库会带来一个几百 KB 的依赖、一套需要在答辩时解释的配置，
 * 以及无障碍属性要额外补的问题。这与项目里「不引入 vue-router、不引入 Pinia」的取舍标准一致。
 *
 * 条形长度按 `items` 里的最大值归一化，而不是按百分比总和：分布图里最大的一项占满整行，
 * 差异才看得清；数值本身仍以文字标在右侧，避免只能从长度目测。
 *
 * @property items     每行一项，`value` 决定长度，`note` 是数值右侧的补充说明（如占比）
 * @property emptyText 没有数据时的提示文案
 */
const props = defineProps<{
  items: { label: string; value: number; note?: string }[]
  emptyText?: string
}>()

/** 归一化基准。全为 0 时取 1，避免出现 0/0 导致的 NaN 宽度。 */
function max() {
  return Math.max(1, ...props.items.map((item) => item.value))
}

/** 单行条形的宽度百分比，保留一位小数即可，再精细也看不出来。 */
function width(value: number) {
  return `${Math.round((value / max()) * 1000) / 10}%`
}
</script>

<template>
  <!-- role/aria 让读屏软件能把图当成一组数据读出来，而不是一堆无意义的空 div -->
  <ul v-if="props.items.length" class="bar-list" role="list">
    <li v-for="item in props.items" :key="item.label" class="bar-row">
      <span class="bar-label">{{ item.label }}</span>
      <span class="bar-track">
        <span class="bar-fill" :style="{ width: width(item.value) }" />
      </span>
      <span class="bar-value">
        {{ item.value }}<small v-if="item.note" class="muted"> · {{ item.note }}</small>
      </span>
    </li>
  </ul>
  <p v-else class="empty">{{ props.emptyText ?? '暂无数据' }}</p>
</template>

<style scoped>
/* 三列网格：标签固定宽度、条形自适应、数值右对齐，这样多行之间的条形起点是对齐的 */
.bar-list { display: grid; gap: 10px; padding: 16px; margin: 0; list-style: none; }
.bar-row { display: grid; grid-template-columns: 120px 1fr 96px; align-items: center; gap: 12px; }
.bar-label { color: var(--muted); font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { height: 14px; border-radius: 7px; background: var(--brand-soft); overflow: hidden; }
.bar-fill { display: block; height: 100%; border-radius: 7px; background: var(--brand); min-width: 2px; }
.bar-value { font-variant-numeric: tabular-nums; font-weight: 600; text-align: right; }
.bar-value small { font-weight: 400; }
</style>
