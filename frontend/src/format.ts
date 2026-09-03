/**
 * 答案与分数的展示格式化。
 *
 * 抽成独立模块是因为同一份答案要在三处显示：教师阅卷弹窗、学生成绩回看、排名表。
 * 三处如果各写一遍格式化逻辑，「未作答」「判断题的 false」这类边界很容易出现不一致。
 */
import type { Option } from './api'

/**
 * 把一道题的作答或标准答案转成可读文本。
 *
 * 四种形态分别处理：
 * - `null` / `undefined` / 空数组 → 「未作答」；
 * - 布尔值 → 「正确」/「错误」。判断题的 `false` 是有效作答，不能当空值处理；
 * - 数组 → 选项键加内容，如 `A. 封装`；传入 `options` 时才拼内容，否则只显示键；
 * - 字符串 → 原样返回（简答题、编程题）。
 */
export function formatAnswer(value: unknown, options?: Option[] | null): string {
  if (value === null || value === undefined) return '未作答'
  if (typeof value === 'boolean') return value ? '正确' : '错误'
  if (Array.isArray(value)) {
    if (!value.length) return '未作答'
    return value
      .map((key) => {
        const text = String(key)
        const option = options?.find((item) => item.key === text)
        return option ? `${text}. ${option.content}` : text
      })
      .join('；')
  }
  const text = String(value)
  return text.trim() ? text : '未作答'
}

/**
 * 分数展示。`null` 表示「不可见或未评分」，统一显示为破折号，
 * 与真实的 `0` 分区分开——两者混在一起会让学生以为自己被判了 0 分。
 */
export function formatScore(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value)
}

/** 时间展示：后端返回 UTC，这里转本地时区并固定 24 小时制，避免上午/下午引起误读。 */
export function formatWhen(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
