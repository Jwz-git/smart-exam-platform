<script setup lang="ts">
/**
 * 应用外壳，负责三件事：按登录状态和角色决定渲染哪套界面、维护侧栏选中项、拼面包屑。
 *
 * 三套界面互斥：
 * - 未登录 → 登录页；
 * - 学生 → 整页答题界面，没有侧栏（对应原型第 4 张图，学生只需要答题这一件事）；
 * - 教师/管理员 → 深色侧栏 + 顶栏 + 内容区（对应原型第 2、3 张图）。
 *
 * 这里用一个 `active` 字符串做视图切换而不是引入 vue-router：本期只有单层页面切换，
 * 没有嵌套路由和 URL 分享需求，少一个依赖也少一处需要在答辩时解释的复杂度。
 * 如果后续要支持「刷新后停留在当前页」，再引入路由更合适。
 */
import { computed, onMounted, ref, watch } from 'vue'
import AppSidebar, { type MenuItem } from './components/AppSidebar.vue'
import AppTopbar from './components/AppTopbar.vue'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import QuestionBankView from './views/QuestionBankView.vue'
import PaperComposeView from './views/PaperComposeView.vue'
import ExamManageView from './views/ExamManageView.vue'
import GradeManageView from './views/GradeManageView.vue'
import UserManageView from './views/UserManageView.vue'
import StatsView from './views/StatsView.vue'
import SystemSettingsView from './views/SystemSettingsView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import StudentExamView from './views/StudentExamView.vue'
import { roleLabels } from './api'
import { isStudent, isTeacher, logout, restore, user } from './session'

/** 侧栏菜单按角色给出；ready=false 的入口来自原型但尚未进入本期范围。 */
const teacherMenu: MenuItem[] = [
  { key: 'home', label: '首页', icon: 'home', ready: true },
  { key: 'questions', label: '题库管理', icon: 'bank', ready: true },
  { key: 'papers', label: '组卷管理', icon: 'paper', ready: true },
  { key: 'exams', label: '考试管理', icon: 'exam', ready: true },
  { key: 'grades', label: '成绩管理', icon: 'grade', ready: true },
  { key: 'stats', label: '统计分析', icon: 'stats', ready: true },
  { key: 'settings', label: '系统设置', icon: 'settings', ready: true },
]
const adminMenu: MenuItem[] = [
  { key: 'home', label: '首页', icon: 'home', ready: true },
  { key: 'users', label: '用户管理', icon: 'users', ready: true },
  { key: 'settings', label: '系统设置', icon: 'settings', ready: true },
]

/** 侧栏按角色取不同菜单。学生不走这套布局，因此这里只需要区分教师和管理员。 */
const menu = computed(() => (isTeacher.value ? teacherMenu : adminMenu))

/** 当前选中的菜单项，也就是内容区渲染哪个视图。 */
const active = ref('home')

/** 各页面的面包屑层级。组卷页有两级，对应原型里的「组卷管理 / 手动组卷」。 */
const crumbLabels: Record<string, string[]> = {
  home: ['首页'],
  questions: ['题库管理'],
  papers: ['组卷管理', '手动组卷'],
  exams: ['考试管理'],
  grades: ['成绩管理'],
  stats: ['统计分析'],
  settings: ['系统设置'],
  users: ['用户管理'],
}
/** 当前页面的面包屑数组。 */
const crumbs = computed(() => crumbLabels[active.value] ?? ['首页'])

/** 面包屑最后一级即页面标题，占位页用它做标题。 */
const pageTitle = computed(() => crumbs.value[crumbs.value.length - 1] ?? '首页')

/**
 * 未开放入口的说明文案，避免用户以为是坏页面。
 *
 * 目前侧栏里的每一项都已实现，这张表因此是空的——保留它是为了后续新增菜单时
 * 仍有一处统一说明「为什么点进来是空的」，而不是临时再补一个占位组件。
 */
const placeholders: Record<string, string> = {}

// 启动时校验本地令牌是否仍然有效，顺便纠正被手工改过的角色信息。
onMounted(restore)

/**
 * 换人登录时把选中项退回首页。
 *
 * `active` 是模块内的普通状态，退出登录不会清掉它。少了这一步，
 * 教师停在「题库管理」退出、换管理员登录后，内容区仍然渲染题库页——
 * 而管理员没有题库权限，一进来就是四个 403。
 * 用 `user?.id` 而不是 `user` 本身做依赖：刷新页面时 `restore()` 会重新赋值 user 对象，
 * 但 id 不变，不该被当成换人。
 */
watch(() => user.value?.id, () => {
  active.value = 'home'
})

/** 切换视图。首页的快捷入口也通过这个函数跳转，因此需要暴露给子组件。 */
function select(key: string) {
  active.value = key
}
</script>

<template>
  <LoginView v-if="!user" />

  <!-- 学生使用整页答题界面，对应原型第 4 张图，没有侧栏。 -->
  <StudentExamView v-else-if="isStudent">
    <template #logout>
      <button class="btn-link" type="button" @click="logout">退出</button>
    </template>
  </StudentExamView>

  <!-- 教师与管理员：侧栏 + 顶栏 + 内容区，对应原型第 2、3 张图 -->
  <div v-else class="layout">
    <AppSidebar :items="menu" :active="active" @select="select" />
    <div class="main">
      <AppTopbar
        :crumbs="crumbs"
        :display-name="user.displayName"
        :role-label="roleLabels[user.role]"
        @logout="logout"
      />
      <!-- 已实现的页面 -->
      <DashboardView v-if="active === 'home'" @go="select" />
      <QuestionBankView v-else-if="active === 'questions'" />
      <PaperComposeView v-else-if="active === 'papers'" />
      <ExamManageView v-else-if="active === 'exams'" />
      <GradeManageView v-else-if="active === 'grades'" />
      <UserManageView v-else-if="active === 'users'" />
      <StatsView v-else-if="active === 'stats'" />
      <SystemSettingsView v-else-if="active === 'settings'" />
      <!-- 其余入口来自原型但未进入本期范围，用占位页说明状态，不假装已完成 -->
      <PlaceholderView
        v-else
        :title="pageTitle"
        :detail="placeholders[active] ?? '该入口未进入本期已确认范围。'"
      />
    </div>
  </div>
</template>
