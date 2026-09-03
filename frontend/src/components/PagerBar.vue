<script setup lang="ts">
import { computed } from 'vue'

/** 分页条：共 N 条 + 每页条数 + 页码，样式对应原型题库管理页底部。 */
const props = defineProps<{ total: number; page: number; size: number }>()

/** 用 update:xxx 事件名，父组件可以直接写 v-model:page / v-model:size。 */
const emit = defineEmits<{ 'update:page': [value: number]; 'update:size': [value: number] }>()

/** 末页页码。总数为 0 时也保留第 1 页，避免分页条整体消失导致布局跳动。 */
const lastPage = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

/** 页数多时折叠中间页码，首尾和当前页附近始终可见。 */
const pages = computed<(number | 'gap')[]>(() => {
  const last = lastPage.value
  if (last <= 7) return Array.from({ length: last }, (_, index) => index + 1)
  const around = new Set<number>([1, last, props.page])
  for (let offset = -1; offset <= 1; offset += 1) {
    const candidate = props.page + offset
    if (candidate > 1 && candidate < last) around.add(candidate)
  }
  if (props.page <= 4) [2, 3, 4, 5].forEach((value) => around.add(value))
  if (props.page >= last - 3) [last - 4, last - 3, last - 2, last - 1].forEach((value) => around.add(value))
  const sorted = [...around].filter((value) => value >= 1 && value <= last).sort((a, b) => a - b)
  const result: (number | 'gap')[] = []
  sorted.forEach((value, position) => {
    const previous = sorted[position - 1]
    if (previous !== undefined && value - previous > 1) result.push('gap')
    result.push(value)
  })
  return result
})

/** 跳页。越界和「点当前页」都直接忽略，避免触发一次无意义的请求。 */
function go(page: number) {
  if (page >= 1 && page <= lastPage.value && page !== props.page) emit('update:page', page)
}
</script>

<template>
  <div class="pager">
    <!-- 三段布局与原型一致：左侧总数、中间每页条数、右侧页码 -->
    <span class="total">共 {{ props.total }} 条</span>
    <select :value="props.size" aria-label="每页条数"
            @change="emit('update:size', Number(($event.target as HTMLSelectElement).value))">
      <option v-for="option in [10, 20, 50]" :key="option" :value="option">{{ option }}条/页</option>
    </select>
    <div class="pages">
      <!-- 箭头按钮只有符号，必须补 aria-label，否则读屏器读不出用途 -->
      <button type="button" :disabled="props.page <= 1" aria-label="上一页" @click="go(props.page - 1)">‹</button>
      <template v-for="(item, index) in pages" :key="`${item}-${index}`">
        <button v-if="item === 'gap'" type="button" class="gap" disabled>…</button>
        <button v-else type="button" :class="{ active: item === props.page }" @click="go(item)">{{ item }}</button>
      </template>
      <button type="button" :disabled="props.page >= lastPage" aria-label="下一页" @click="go(props.page + 1)">›</button>
    </div>
  </div>
</template>
