<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, type KnowledgePoint, type Question, type User } from './api'

const token = ref(localStorage.getItem('examToken') ?? '')
const user = ref<User | null>(JSON.parse(localStorage.getItem('examUser') ?? 'null') as User | null)
const username = ref('teacher'), password = ref('ExamDemo123!'), error = ref(''), busy = ref(false)
const points = ref<KnowledgePoint[]>([]), questions = ref<Question[]>([]), pointName = ref('')
const filters = reactive({ type: '', difficulty: '', knowledgePointId: '' })
const form = reactive({ type: 'SINGLE_CHOICE', stem: '', difficulty: 'MEDIUM', suggestedScore: 10,
  knowledgePointId: '', optionsText: 'A|选项 A\nB|选项 B', answer: 'A', explanation: '' })
const isTeacher = computed(() => user.value?.role === 'TEACHER')
const types: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', SHORT_ANSWER: '简答题' }
const difficulties: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }

async function login() {
  error.value = ''; busy.value = true
  try { const result = await api.login(username.value, password.value); token.value = result.accessToken; user.value = result.user
    localStorage.setItem('examToken', token.value); localStorage.setItem('examUser', JSON.stringify(user.value)); if (isTeacher.value) await loadBank()
  } catch (reason) { error.value = (reason as Error).message } finally { busy.value = false }
}
async function logout() { try { if (token.value) await api.logout(token.value) } finally { token.value = ''; user.value = null; localStorage.removeItem('examToken'); localStorage.removeItem('examUser') } }
async function loadBank() {
  if (!token.value) return
  try { points.value = await api.points(token.value); const query = new URLSearchParams()
    Object.entries(filters).forEach(([key, value]) => { if (value) query.set(key, value) }); questions.value = (await api.questions(token.value, query)).items
    if (!form.knowledgePointId && points.value[0]) form.knowledgePointId = String(points.value[0].id)
  } catch (reason) { error.value = (reason as Error).message }
}
async function addPoint() { if (!pointName.value.trim()) return; try { const point = await api.createPoint(token.value, pointName.value.trim()); pointName.value = ''; points.value.push(point); form.knowledgePointId = String(point.id) } catch (reason) { error.value = (reason as Error).message } }
function answer() { if (form.type === 'TRUE_FALSE') return form.answer.trim().toLowerCase() === 'true'; if (form.type === 'SHORT_ANSWER') return form.answer.trim(); return form.answer.split(',').map(v => v.trim().toUpperCase()).filter(Boolean) }
function options() { if (!form.type.includes('CHOICE')) return []; return form.optionsText.split('\n').filter(Boolean).map(line => { const [key, ...content] = line.split('|'); return { key: key?.trim(), content: content.join('|').trim() } }) }
async function addQuestion() { error.value = ''; try { await api.createQuestion(token.value, { type: form.type, stem: form.stem, difficulty: form.difficulty, standardAnswer: answer(), explanation: form.explanation, suggestedScore: form.suggestedScore, knowledgePointId: Number(form.knowledgePointId), options: options() }); form.stem = ''; await loadBank() } catch (reason) { error.value = (reason as Error).message } }
async function removeQuestion(id: number) { if (!window.confirm('确认删除这道题吗？')) return; try { await api.deleteQuestion(token.value, id); await loadBank() } catch (reason) { error.value = (reason as Error).message } }
onMounted(() => { if (isTeacher.value) void loadBank() })
</script>

<template>
  <main v-if="!user" class="login-shell"><form class="login-card" @submit.prevent="login">
    <p class="eyebrow">SMART EXAM PLATFORM</p><h1>登录在线题库</h1>
    <label>账号<input v-model="username" autocomplete="username" required /></label><label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
    <p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button><p class="hint">演示教师：teacher / ExamDemo123!</p>
  </form></main>
  <main v-else class="app-shell"><header><div><p class="eyebrow">SMART EXAM</p><h1>教师题库</h1></div><div class="account"><span>{{ user.displayName }} · {{ user.role }}</span><button @click="logout">退出</button></div></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p><section v-if="!isTeacher" class="panel"><h2>登录成功</h2><p>当前角色暂未开放此页面的业务功能。</p></section>
    <template v-else><section class="toolbar panel">
      <select v-model="filters.type"><option value="">全部题型</option><option v-for="(label,key) in types" :key="key" :value="key">{{ label }}</option></select>
      <select v-model="filters.difficulty"><option value="">全部难度</option><option v-for="(label,key) in difficulties" :key="key" :value="key">{{ label }}</option></select>
      <select v-model="filters.knowledgePointId"><option value="">全部知识点</option><option v-for="point in points" :key="point.id" :value="String(point.id)">{{ point.name }}</option></select><button class="primary" @click="loadBank">筛选</button>
      <div class="point-add"><input v-model="pointName" placeholder="新知识点名称" /><button @click="addPoint">添加知识点</button></div>
    </section><section class="content-grid"><form class="panel editor" @submit.prevent="addQuestion"><h2>新增题目</h2>
      <label>题型<select v-model="form.type"><option v-for="(label,key) in types" :key="key" :value="key">{{ label }}</option></select></label><label>题干<textarea v-model="form.stem" required /></label>
      <div class="two"><label>难度<select v-model="form.difficulty"><option v-for="(label,key) in difficulties" :key="key" :value="key">{{ label }}</option></select></label><label>建议分值<input v-model.number="form.suggestedScore" type="number" min="0.1" step="0.1" /></label></div>
      <label>知识点<select v-model="form.knowledgePointId" required><option value="" disabled>请选择</option><option v-for="point in points" :key="point.id" :value="String(point.id)">{{ point.name }}</option></select></label>
      <label v-if="form.type.includes('CHOICE')">选项（每行：键|内容）<textarea v-model="form.optionsText" /></label><label>标准答案 <small v-if="form.type.includes('CHOICE')">（多项用英文逗号分隔）</small><input v-model="form.answer" required /></label><label>解析<textarea v-model="form.explanation" /></label><button class="primary">保存题目</button>
    </form><section class="panel list"><div class="list-title"><h2>题目列表</h2><span>{{ questions.length }} 道</span></div><article v-for="question in questions" :key="question.id"><div class="badges"><span>{{ types[question.type] }}</span><span>{{ difficulties[question.difficulty] }}</span><span>{{ question.knowledgePointName }}</span></div><h3>{{ question.stem }}</h3><p>{{ question.suggestedScore }} 分</p><button class="danger" @click="removeQuestion(question.id)">删除</button></article><p v-if="!questions.length" class="empty">暂无符合条件的题目</p></section></section></template>
  </main>
</template>

<style>
:root{color:#17324d;background:#eef3f6;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}*{box-sizing:border-box}body{margin:0;min-width:320px;min-height:100vh}button,input,select,textarea{font:inherit}button{cursor:pointer;border:1px solid #cbd8df;border-radius:9px;background:white;padding:9px 14px;color:#24445e}input,select,textarea{width:100%;border:1px solid #cbd8df;border-radius:9px;padding:10px;background:#fff;color:#17324d}textarea{min-height:82px;resize:vertical}label{display:grid;gap:7px;font-weight:600;color:#405c70}.eyebrow{margin:0 0 6px;color:#147d78;font-size:12px;font-weight:800;letter-spacing:.14em}h1,h2,h3,p{margin-top:0}.primary{background:#147d78;color:#fff;border-color:#147d78;font-weight:700}.error{padding:10px 14px;border-radius:9px;background:#fff0ee;color:#a63225}.login-shell{min-height:100vh;display:grid;place-items:center;padding:24px}.login-card{display:grid;gap:18px;width:min(440px,100%);padding:40px;border-radius:22px;background:#fff;box-shadow:0 18px 60px rgb(30 65 90 / 12%)}.login-card h1{font-size:34px}.hint{margin:0;color:#708697;font-size:13px}.app-shell{width:min(1280px,100%);margin:auto;padding:28px}.app-shell>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:22px}.account{display:flex;gap:14px;align-items:center}.panel{background:#fff;border:1px solid #dce6eb;border-radius:15px;padding:20px}.toolbar{display:grid;grid-template-columns:repeat(3,minmax(130px,1fr)) auto minmax(260px,1.4fr);gap:12px;margin-bottom:18px}.point-add{display:flex;gap:8px}.content-grid{display:grid;grid-template-columns:minmax(320px,420px) 1fr;gap:18px}.editor{display:grid;gap:15px;align-self:start}.two{display:grid;grid-template-columns:1fr 1fr;gap:12px}.list-title{display:flex;justify-content:space-between}.list article{position:relative;padding:18px 90px 18px 0;border-top:1px solid #e5ecef}.list article h3{margin:10px 0 8px}.list article p{margin:0;color:#708697}.badges{display:flex;gap:7px;flex-wrap:wrap}.badges span{padding:4px 8px;border-radius:99px;background:#e8f4f3;color:#116d68;font-size:12px}.danger{position:absolute;right:0;top:20px;color:#b13b31}.empty{color:#708697;text-align:center;padding:40px}@media(max-width:900px){.toolbar,.content-grid{grid-template-columns:1fr}.app-shell>header{align-items:flex-start}.account{flex-direction:column;align-items:flex-end}}@media(max-width:560px){.app-shell{padding:18px}.login-card{padding:28px 22px}.two{grid-template-columns:1fr}.account span{display:none}}
</style>
