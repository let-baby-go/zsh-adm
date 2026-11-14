// Console-wide state shared by the shell and every page. Page-local state lives in the page component.
import { computed, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { api, post } from './api'

const emptyPolicy = () => ({ directoryScope: 'ORGANIZATION', sharingEnabled: true, revision: 0, bodyRetentionDays: 0 })

export const me = ref(null)
export const page = ref('members')
export const error = ref('')
export const memberSummary = ref({ total: 0, active: 0, admins: 0 })
export const AUDIT_PAGE = 50
export const audits = ref([])
export const auditOffset = ref(0)
export const organization = ref({ name: 'WorkDSH 企业', departments: [], profiles: [], policy: emptyPolicy() })
export const policy = reactive(emptyPolicy())
/** Incremented by the shell's refresh button; pages with their own data reload when it changes. */
export const refreshTick = ref(0)

export const owner = computed(() => me.value?.role === 'OWNER')
export const admin = computed(() => ['OWNER', 'ADMIN'].includes(me.value?.role))

export const roleLabel = r => ({ OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' })[r] || r
export const profile = id => organization.value.profiles.find(p => p.memberId === id) || {}
export const departmentName = id => organization.value.departments.find(d => d.id === id)?.name || '未分配部门'
export const deptOptions = computed(() => organization.value.departments.map(d => ({ value: d.id, label: d.name })))

export async function run(fn) {
  error.value = ''
  try { return await fn() } catch (e) { error.value = e.message; Message.error(e.message); throw e }
}

export async function load() {
  if (!me.value || me.value.mustChangePassword) return
  if (!admin.value) return
  const [summary, o, a] = await Promise.all([api('/admin/members/summary'), api('/admin/organization'), auditPage()])
  memberSummary.value = summary
  organization.value = o
  audits.value = a
  Object.assign(policy, o.policy)
}

const auditPage = () => api(`/admin/audit?offset=${auditOffset.value}&limit=${AUDIT_PAGE}`)

export async function loadAudits(offset) {
  auditOffset.value = Math.max(0, offset)
  try { audits.value = await run(auditPage) } catch {}
}

export function navigate(key) { page.value = key }

export async function refresh() {
  try { await run(load); refreshTick.value++ } catch {}
}

export async function logout() {
  try {
    await post('/auth/logout', {})
    me.value = null
    memberSummary.value = { total: 0, active: 0, admins: 0 }
    audits.value = []
    auditOffset.value = 0
  } catch (e) { Message.error(e.message) }
}
