/**
 * 登录态。用模块级 ref 而不是引入 Pinia：需要共享的状态只有令牌和当前用户两项，
 * 引入一整套状态管理库并不划算，`AGENTS.md` 也要求新增依赖前先确认必要性。
 */
import { computed, ref } from 'vue'
import { api, setUnauthorizedHandler, type User } from './api'

const TOKEN_KEY = 'examToken'
const USER_KEY = 'examUser'

/** 隐私模式、测试环境或存储配额用尽时 localStorage 可能不可用，读写都不应让应用崩掉。 */
const storage = {
  get(key: string): string | null {
    try {
      return globalThis.localStorage?.getItem(key) ?? null
    } catch {
      return null
    }
  },
  set(key: string, value: string) {
    try {
      globalThis.localStorage?.setItem(key, value)
    } catch {
      /* 存不下就只保留内存中的登录态。 */
    }
  },
  remove(key: string) {
    try {
      globalThis.localStorage?.removeItem(key)
    } catch {
      /* 同上。 */
    }
  },
}

function readUser(): User | null {
  try {
    return JSON.parse(storage.get(USER_KEY) ?? 'null') as User | null
  } catch {
    return null
  }
}

export const token = ref(storage.get(TOKEN_KEY) ?? '')
export const user = ref<User | null>(readUser())
/** 令牌失效时给用户一句解释，而不是让页面停在空白的登录页。 */
export const expiredNotice = ref('')

/**
 * 角色判断。注意这只用于决定界面显示什么，不构成任何权限保证——
 * 用户完全可以改掉 localStorage 里的角色，真正的拦截在后端每个接口上。
 */
export const isTeacher = computed(() => user.value?.role === 'TEACHER')
export const isStudent = computed(() => user.value?.role === 'STUDENT')
export const isAdmin = computed(() => user.value?.role === 'ADMIN')

/** 清空内存与本地存储中的登录态。退出、令牌失效和校验失败三条路径共用。 */
function clear() {
  token.value = ''
  user.value = null
  storage.remove(TOKEN_KEY)
  storage.remove(USER_KEY)
}

/** 登录成功后写入内存与 localStorage，失败时把异常抛给调用方由页面展示。 */
export async function login(username: string, password: string) {
  const result = await api.login(username, password)
  token.value = result.accessToken
  user.value = result.user
  storage.set(TOKEN_KEY, result.accessToken)
  storage.set(USER_KEY, JSON.stringify(result.user))
  expiredNotice.value = ''
}

/**
 * 退出登录。
 *
 * 即使退出接口调用失败（比如令牌已过期返回 401），本地登录态也必须清掉，
 * 所以清理逻辑放在 finally 里。
 */
export async function logout() {
  try {
    if (token.value) await api.logout(token.value)
  } finally {
    clear()
  }
}

/**
 * 启动时用 /auth/me 验证本地令牌，顺便纠正被手工改过的 localStorage 角色。
 * 令牌已过期或账号被禁用时直接退回登录页。
 */
export async function restore() {
  if (!token.value) return
  try {
    const current = await api.me(token.value)
    user.value = current
    storage.set(USER_KEY, JSON.stringify(current))
  } catch {
    clear()
  }
}

/**
 * 注册 401 回调。任何接口返回 401 都会走到这里，把用户退回登录页并给出解释。
 * 少了这一步，令牌过期后页面会停在「已登录但每个请求都失败」的死状态。
 */
setUnauthorizedHandler(() => {
  clear()
  expiredNotice.value = '登录状态已失效，请重新登录。'
})
