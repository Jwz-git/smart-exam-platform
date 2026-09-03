<script setup lang="ts">
/**
 * 用户管理页（管理员）：查询、新增、启用/停用。
 *
 * 范围与 `plan.md` 第 2.1 节一致，只做这三件事——没有改密码、改角色和删除。
 * 删除是刻意不做的：存在答卷的账号一旦删除，历史成绩就失去归属，因此只允许停用。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import PagerBar from '../components/PagerBar.vue'
import { api, roleLabels, type AdminUser, type Role } from '../api'
import { formatWhen } from '../format'
import { token, user } from '../session'

/** 筛选条件。空字符串表示「全部」，拼查询串时会被跳过。 */
const filters = reactive({ keyword: '', role: '', status: '' })
const page = ref(1)
const size = ref(10)
const total = ref(0)
const users = ref<AdminUser[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

/** 新增用户表单。密码只在提交时发送，成功后立即清空，不留在内存里。 */
const form = reactive({ username: '', password: '', displayName: '', role: 'STUDENT' as Role })

/** 拉取当前筛选条件下的用户列表。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.users(token.value, { ...filters, page: page.value, size: size.value })
    users.value = result.items
    total.value = result.total
    // 停用后筛选条件可能让当前页变空，回退一页避免出现空表配非零总数。
    if (!result.items.length && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (reason) {
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

/** 点击查询。页码必须复位到 1，否则在第 3 页改条件会查到一个不存在的页。 */
function search() {
  page.value = 1
  void load()
}

/** 清空筛选条件并重新查询。 */
function resetFilters() {
  Object.assign(filters, { keyword: '', role: '', status: '' })
  search()
}

watch([page, size], () => void load())
onMounted(load)

/**
 * 新增用户。
 *
 * 前端校验与后端 `CreateUserRequest` 的约束对齐：用户名 3–64 位且只含字母数字下划线点减号、
 * 密码至少 8 位。校验重复一遍只是少一次失败往返，后端仍会完整校验。
 */
async function create() {
  error.value = ''
  notice.value = ''
  const username = form.username.trim()
  const displayName = form.displayName.trim()
  if (!/^[A-Za-z0-9_.-]{3,64}$/.test(username)) {
    error.value = '用户名需为 3–64 位，且只能包含字母、数字、下划线、点和减号'
    return
  }
  if (form.password.length < 8) { error.value = '密码至少 8 位'; return }
  if (!displayName) { error.value = '请填写显示名称'; return }
  try {
    const created = await api.createUser(token.value, {
      username, password: form.password, displayName, role: form.role,
    })
    notice.value = `已新增${roleLabels[created.role]}「${created.displayName}」，可以立即登录。`
    Object.assign(form, { username: '', password: '', displayName: '', role: 'STUDENT' })
    search()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}

/**
 * 启用或停用用户。
 *
 * 停用是影响面较大的操作：该账号会立即无法登录，此前签发的令牌也会当场失效，
 * 因此加二次确认；启用是恢复性操作，不打扰。
 */
async function toggleStatus(target: AdminUser) {
  const next = target.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED'
      && !window.confirm(`停用后「${target.displayName}」将立即无法登录，已登录的会话也会失效，确认停用吗？`)) return
  error.value = ''
  notice.value = ''
  try {
    await api.setUserStatus(token.value, target.id, next)
    notice.value = next === 'ACTIVE' ? '账号已启用。' : '账号已停用，历史数据保留。'
    await load()
  } catch (reason) {
    error.value = (reason as Error).message
  }
}
</script>

<template>
  <section class="content">
    <div class="page-head"><h2>用户管理</h2></div>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert ok" role="status">{{ notice }}</p>

    <!-- 新增表单。字段横排一行，与题库页筛选卡保持同一种视觉节奏 -->
    <div class="card card-pad filter-bar">
      <label>用户名 <input v-model="form.username" maxlength="64" placeholder="teacher2" style="min-width: 150px" /></label>
      <!-- type=password 避免密码明文出现在屏幕上；它也不会被写进任何日志或响应 -->
      <label>初始密码 <input v-model="form.password" type="password" maxlength="72" placeholder="至少 8 位" style="min-width: 150px" /></label>
      <label>显示名称 <input v-model="form.displayName" maxlength="64" placeholder="张老师" style="min-width: 130px" /></label>
      <label>角色
        <select v-model="form.role">
          <option v-for="(label, key) in roleLabels" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <button class="btn btn-primary" type="button" @click="create">新增用户</button>
    </div>

    <!-- 筛选卡。关键词同时匹配登录名和显示名，由后端一条 SQL 完成 -->
    <div class="card card-pad filter-bar">
      <label>关键词 <input v-model="filters.keyword" placeholder="用户名或显示名" @keyup.enter="search" /></label>
      <label>角色
        <select v-model="filters.role">
          <option value="">全部</option>
          <option v-for="(label, key) in roleLabels" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label>状态
        <select v-model="filters.status">
          <option value="">全部</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
        </select>
      </label>
      <button class="btn btn-primary" type="button" @click="search">查询</button>
      <button class="btn" type="button" @click="resetFilters">重置</button>
    </div>

    <div class="card">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 70px">编号</th>
            <th style="width: 160px">用户名</th>
            <th>显示名称</th>
            <th style="width: 90px">角色</th>
            <th style="width: 70px">状态</th>
            <th style="width: 180px">创建时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in users" :key="item.id">
            <td class="muted">{{ item.id }}</td>
            <td class="cell-strong">{{ item.username }}</td>
            <td>{{ item.displayName }}</td>
            <td>{{ roleLabels[item.role] }}</td>
            <td :class="item.status === 'ACTIVE' ? 'state-on' : 'state-off'">
              {{ item.status === 'ACTIVE' ? '启用' : '停用' }}
            </td>
            <td class="muted">{{ formatWhen(item.createdAt) }}</td>
            <!-- 当前登录的管理员不能停用自己，否则单管理员系统会被一次误操作锁死；
                 后端同样会拒绝，这里只是提前把按钮禁掉并说明原因 -->
            <td class="ops">
              <button
                class="btn-link"
                type="button"
                :disabled="item.id === user?.id"
                :title="item.id === user?.id ? '不能停用当前登录的账号' : ''"
                @click="toggleStatus(item)"
              >{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button>
            </td>
          </tr>
          <tr v-if="!users.length && !loading"><td colspan="7"><p class="empty">暂无符合条件的用户</p></td></tr>
          <tr v-if="loading"><td colspan="7"><p class="empty">加载中…</p></td></tr>
        </tbody>
      </table>
      <PagerBar v-model:page="page" v-model:size="size" :total="total" />
    </div>
  </section>
</template>
