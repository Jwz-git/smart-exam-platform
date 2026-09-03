<script setup lang="ts">
/** 顶栏：面包屑 + 右侧账号，对应原型第 2、3 张图。 */
/**
 * @property crumbs      面包屑层级，最后一项显示为高亮
 * @property displayName 当前用户姓名
 * @property roleLabel   角色中文名
 */
const props = defineProps<{ crumbs: string[]; displayName: string; roleLabel: string }>()

/** 退出登录由外层处理，组件本身不碰登录态。 */
const emit = defineEmits<{ logout: [] }>()
</script>

<template>
  <header class="topbar">
    <!-- 汉堡图标对应原型顶栏左侧的折叠标记；本期侧栏不支持折叠，因此只作装饰，用 aria-hidden 让读屏器跳过 -->
    <svg width="18" height="18" viewBox="0 0 24 24" stroke="#6b7280" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
    <!-- 面包屑：非末级用普通文字，末级加粗，中间用 / 分隔 -->
    <p class="crumbs">
      <template v-for="(crumb, index) in props.crumbs" :key="crumb">
        <span v-if="index"> / </span><b v-if="index === props.crumbs.length - 1">{{ crumb }}</b><span v-else>{{ crumb }}</span>
      </template>
    </p>
    <div class="account">
      <!-- 取姓名首字作头像占位，避免为演示账号准备图片资源 -->
      <span class="avatar" aria-hidden="true">{{ props.displayName.slice(0, 1) }}</span>
      <span class="muted">{{ props.displayName }} · {{ props.roleLabel }}</span>
      <button class="btn-link" type="button" @click="emit('logout')">退出</button>
    </div>
  </header>
</template>
