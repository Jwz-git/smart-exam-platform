<script setup lang="ts">
/**
 * 未开放入口的占位页。
 *
 * 侧栏保留了原型里的全部菜单以便界面对得上设计稿，但成绩管理、统计分析等功能确实还没做。
 * 与其让用户点到一个空白页面，不如明确说明「未开放」以及计划何时完成——
 * 这也符合项目规范里「不把计划写成已完成」的要求。
 *
 * @property title  页面标题，取自面包屑最后一级
 * @property detail 未开放的具体说明，包含计划时间或范围结论
 */
const props = defineProps<{ title: string; detail: string }>()
</script>

<template>
  <section class="content">
    <div class="page-head"><h2>{{ props.title }}</h2></div>
    <div class="card card-pad">
      <p class="cell-strong">该功能尚未开放。</p>
      <p class="muted" style="margin-top: 8px">{{ props.detail }}</p>
    </div>
  </section>
</template>
