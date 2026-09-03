const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface User { id: number; username: string; displayName: string; role: 'ADMIN' | 'TEACHER' | 'STUDENT' }
export interface KnowledgePoint { id: number; name: string; description?: string }
export interface Question { id: number; type: string; stem: string; difficulty: string; suggestedScore: number; knowledgePointId: number; knowledgePointName: string; options: Array<{ key: string; content: string }> }
export interface PaperQuestion { id: number; questionId: number; displayOrder: number; score: number; type: string; stem: string; options: Array<{ key: string; content: string }> }
export interface Paper { id: number; name: string; durationMinutes: number; totalScore: number; status: string; questions: PaperQuestion[] }
export interface Exam { id: number; name: string; paperId: number; paperName: string; durationMinutes: number; totalScore: number; startAt: string; endAt: string; status: string; submissionId?: number; submissionStatus?: string }
export interface Submission { id: number; examId: number; status: string; startedAt: string; submittedAt?: string; objectiveScore: number; questions: PaperQuestion[] }
interface Envelope<T> { data: T }

async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { message?: string }
    throw new Error(error.message || `请求失败（${response.status}）`)
  }
  if (response.status === 204) return undefined as T
  return ((await response.json()) as Envelope<T>).data
}

export const api = {
  login: (username: string, password: string) => request<{ accessToken: string; user: User }>('/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: (token: string) => request<void>('/v1/auth/logout', { method: 'POST' }, token),
  points: (token: string) => request<KnowledgePoint[]>('/v1/knowledge-points', {}, token),
  createPoint: (token: string, name: string) => request<KnowledgePoint>('/v1/knowledge-points', { method: 'POST', body: JSON.stringify({ name }) }, token),
  questions: (token: string, query: URLSearchParams) => request<{ items: Question[]; total: number }>(`/v1/questions?${query}`, {}, token),
  createQuestion: (token: string, body: unknown) => request<Question>('/v1/questions', { method: 'POST', body: JSON.stringify(body) }, token),
  deleteQuestion: (token: string, id: number) => request<void>(`/v1/questions/${id}`, { method: 'DELETE' }, token),
  papers: (token: string) => request<Paper[]>('/v1/papers', {}, token),
  createPaper: (token: string, body: unknown) => request<Paper>('/v1/papers', { method: 'POST', body: JSON.stringify(body) }, token),
  publishPaper: (token: string, id: number) => request<Paper>(`/v1/papers/${id}/publish`, { method: 'POST' }, token),
  exams: (token: string) => request<Exam[]>('/v1/exams', {}, token),
  createExam: (token: string, body: unknown) => request<Exam>('/v1/exams', { method: 'POST', body: JSON.stringify(body) }, token),
  publishExam: (token: string, id: number) => request<Exam>(`/v1/exams/${id}/publish`, { method: 'POST' }, token),
  startSubmission: (token: string, examId: number) => request<Submission>(`/v1/exams/${examId}/submissions`, { method: 'POST' }, token),
  saveAnswers: (token: string, submissionId: number, answers: unknown[]) => request<Submission>(`/v1/submissions/${submissionId}/answers`, { method: 'PUT', body: JSON.stringify({ answers }) }, token),
  submit: (token: string, submissionId: number) => request<{ id: number; status: string; objectiveScore: number }>(`/v1/submissions/${submissionId}/submit`, { method: 'POST' }, token),
}
