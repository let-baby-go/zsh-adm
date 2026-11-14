import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'

const responses = {}
vi.mock('../api', () => ({
  api: vi.fn(async path => {
    const key = Object.keys(responses).find(prefix => path.startsWith(prefix))
    if (!key) throw Object.assign(new Error('unexpected ' + path), { status: 404 })
    return structuredClone(responses[key])
  }),
  post: vi.fn(async () => ({})),
  patch: vi.fn(async () => ({})),
}))

const { auditActions, auditLabel } = await import('../labels')
const store = await import('../store')
const { pages } = await import('../pages')
const App = (await import('../App.vue')).default

const organization = { name: '测试企业', departments: [], profiles: [], policy: { directoryScope: 'ORGANIZATION', sharingEnabled: true, revision: 1, bodyRetentionDays: 30 } }

function signIn(role) {
  Object.keys(responses).forEach(k => delete responses[k])
  Object.assign(responses, {
    '/auth/me': { id: 'm1', email: 'a@example.test', displayName: '管理员', role, mustChangePassword: false },
    '/admin/members/summary': { total: 1, active: 1, admins: role === 'MEMBER' ? 0 : 1 },
    '/admin/members/search': { items: [{ id: 'm1', email: 'a@example.test', displayName: '管理员', role, active: true, mustChangePassword: false, revision: 1 }], total: 1 },
    '/admin/organization': organization,
    '/admin/audit': [],
    '/admin/sessions': [],
    '/admin/model-providers': { revision: 0, providers: [] },
    '/admin/model-gateway': { hasAccessKey: false },
  })
  store.me.value = null
  store.page.value = 'members'
}

/** Pages are async components: wait for their chunks, then for the requests they start. */
async function settle() {
  for (let i = 0; i < 3; i++) { await vi.dynamicImportSettled(); await flushPromises() }
}

async function mountApp() {
  const wrapper = mount(App, { global: { plugins: [ArcoVue] }, attachTo: document.body })
  await settle()
  return wrapper
}

describe('audit labels', () => {
  it('label exactly the actions the server records', () => {
    const root = join(__dirname, '../../../server/src/main/java/com/techflag/workdsh/admin')
    const recorded = new Set()
    for (const file of readdirSync(root)) {
      const source = readFileSync(join(root, file), 'utf8')
      for (const match of source.matchAll(/audit\.record\([^,]+,\s*(?:[^"]*\?\s*)?"([a-z.]+)"(?:\s*:\s*"([a-z.]+)")?/g)) {
        recorded.add(match[1])
        if (match[2]) recorded.add(match[2])
      }
    }
    expect([...recorded].sort()).toEqual([...auditActions].sort())
    expect(auditLabel('runtime.credential.revoked')).toBe('runtime.credential.revoked')
  })
})

describe('console shell', () => {
  beforeEach(() => { document.body.innerHTML = '' })

  it('shows members a notice without a broken DSH button', async () => {
    signIn('MEMBER')
    const wrapper = await mountApp()
    expect(wrapper.text()).toContain('你是普通成员，无权访问组织管理')
    expect(wrapper.text()).not.toContain('打开我的 DSH')
    wrapper.unmount()
  })

  it('renders every page from the page table for administrators', async () => {
    signIn('OWNER')
    const wrapper = await mountApp()
    for (const [key, entry] of Object.entries(pages)) {
      store.navigate(key)
      await settle()
      expect(wrapper.find('h1').text()).toBe(entry.title)
    }
    store.navigate('members')
    await settle()
    expect(wrapper.text()).toContain('成员 1 人')
    wrapper.unmount()
  })

  it('shows the organization body retention on the settings page', async () => {
    signIn('OWNER')
    const wrapper = await mountApp()
    store.navigate('settings')
    await settle()
    expect(wrapper.text()).toContain('会话正文保留')
    expect(wrapper.find('.policy-panel .arco-input-number input').element.value).toBe('30')
    wrapper.unmount()
  })

  it('asks the server for one filtered page of members', async () => {
    signIn('OWNER')
    const { api } = await import('../api')
    const wrapper = await mountApp()
    const searches = api.mock.calls.map(([path]) => path).filter(path => path.startsWith('/admin/members/search'))
    expect(searches.at(-1)).toBe('/admin/members/search?q=&status=all&offset=0&limit=10')
    expect(api.mock.calls.some(([path]) => path === '/admin/members')).toBe(false)
    wrapper.unmount()
  })
})
