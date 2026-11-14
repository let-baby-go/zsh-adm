import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { modelProblems } from '../modelValidation'
import { consoleMessage } from '../messages'
import { api } from '../api'

const provider = overrides => ({
  id: 'company', displayName: '公司模型', baseUrl: 'https://api.example.com', protocol: 'openai-completions',
  enabled: true, hasKey: true, apiKey: '', models: [{ id: 'model-a', displayName: 'A', contextWindow: 1000, maxOutputTokens: 100, images: false }],
  ...overrides,
})

describe('model configuration checks', () => {
  it('accepts a valid configuration', () => {
    expect(modelProblems([provider()], { hasAccessKey: true }, '')).toEqual([])
  })

  it('names every problem the server would reject', () => {
    const problems = modelProblems([
      provider({ id: 'Bad Id', baseUrl: 'http://api.example.com' }),
      provider({ id: 'second', displayName: '', hasKey: false, models: [] }),
      provider({ id: 'third', models: [{ id: 'model-a', contextWindow: 10, maxOutputTokens: 20 }] }),
    ], { hasAccessKey: false }, 'short')
    expect(problems.join('\n')).toMatch('接口 ID 需小写字母开头')
    expect(problems.join('\n')).toMatch('必须是 https://')
    expect(problems.join('\n')).toMatch('请填写显示名称')
    expect(problems.join('\n')).toMatch('启用前请填写供应商 API Key')
    expect(problems.join('\n')).toMatch('启用前请添加至少一个模型')
    expect(problems.join('\n')).toMatch('最大输出 token 不能超过上下文窗口')
    expect(problems.join('\n')).toMatch('启用的接口之间模型 ID 不能重复')
    expect(problems.join('\n')).toMatch('内部访问 Key 需为 32–512 位')
  })

  it('requires an internal key only until one is saved', () => {
    expect(modelProblems([], { hasAccessKey: false }, '')).toEqual(['请设置至少 32 位的内部访问 Key'])
    expect(modelProblems([], { hasAccessKey: true }, '')).toEqual([])
  })
})

describe('console error messages', () => {
  it('only translate messages the server actually sends', () => {
    const root = join(__dirname, '../../../server/src/main/java/com/techflag/workdsh/admin')
    const source = readdirSync(root).map(file => readFileSync(join(root, file), 'utf8')).join('\n')
    const translated = readFileSync(join(__dirname, '../messages.js'), 'utf8').matchAll(/^\s+'([^']+)': '/gm)
    for (const [, english] of translated) expect(source, english).toContain(`"${english}"`)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('shows translated server detail, and a status hint when there is none', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ detail: 'Invalid credentials' }), { status: 401 })))
    await expect(api('/auth/browser-login')).rejects.toThrow('账号或密码不正确')
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Bad Gateway</html>', { status: 502 })))
    await expect(api('/admin/members/summary')).rejects.toThrow('服务暂时不可用 (502)')
    expect(consoleMessage('数据已变化，请刷新后重试')).toBe('数据已变化，请刷新后重试')
  })
})
