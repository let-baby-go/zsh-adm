// The server's model-interface rules (ModelProviders.update, ModelGatewayKeys.update), checked before saving so
// the administrator sees every problem at once, named by interface and model, instead of one generic 400.
const PROVIDER_ID = /^[a-z][a-z0-9_-]{0,63}$/
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
const MAX_LIMIT = 100000000

function httpsProblem(value) {
  let url
  try { url = new URL(value) } catch { return '上游 API 地址无效' }
  if (url.protocol !== 'https:') return '上游 API 地址必须是 https://'
  if (url.username || url.password || url.search || url.hash) return '上游 API 地址不能包含账号、查询参数或 #'
  if (value.length > 1024) return '上游 API 地址过长'
  return null
}

const validLimit = value => value == null || value === '' || (Number.isInteger(value) && value > 0 && value <= MAX_LIMIT)

/** Returns problems as readable lines; empty means the server should accept the configuration. */
export function modelProblems(providers, gateway, accessKey) {
  const problems = []
  if (providers.length > 32) problems.push('最多配置 32 个接口')
  const ids = new Set(), enabledModels = new Map()
  providers.forEach((p, index) => {
    const name = `接口「${p.displayName || p.id || index + 1}」`
    if (!PROVIDER_ID.test(p.id || '')) problems.push(`${name}：接口 ID 需小写字母开头，只含小写字母、数字、- 和 _，最长 64 位`)
    else if (ids.has(p.id)) problems.push(`${name}：接口 ID「${p.id}」重复`)
    ids.add(p.id)
    if (!p.displayName?.trim()) problems.push(`${name}：请填写显示名称`)
    else if (p.displayName.length > 128) problems.push(`${name}：显示名称最多 128 字`)
    const url = httpsProblem(p.baseUrl || '')
    if (url) problems.push(`${name}：${url}`)
    if ((p.apiKey || '').length > 8192 || /[\u0000-\u001f\u007f]/.test(p.apiKey || '')) problems.push(`${name}：供应商 API Key 含非法字符或过长`)
    if (p.models.length > 64) problems.push(`${name}：每个接口最多 64 个模型`)
    if (p.enabled && !p.hasKey && !p.apiKey) problems.push(`${name}：启用前请填写供应商 API Key`)
    if (p.enabled && !p.models.length) problems.push(`${name}：启用前请添加至少一个模型`)
    const own = new Set()
    p.models.forEach((m, modelIndex) => {
      const model = `${name}的模型「${m.id || modelIndex + 1}」`
      if (!MODEL_ID.test(m.id || '')) problems.push(`${model}：模型 ID 需字母或数字开头，只含字母、数字和 . _ : / -，最长 128 位`)
      else if (own.has(m.id)) problems.push(`${model}：同一接口内模型 ID 重复`)
      own.add(m.id)
      if ((m.displayName || '').length > 128) problems.push(`${model}：模型名称最多 128 字`)
      if (!validLimit(m.contextWindow) || !validLimit(m.maxOutputTokens)) problems.push(`${model}：上下文窗口和最大输出 token 需为 1–100000000 的整数`)
      else if (m.contextWindow && m.maxOutputTokens && m.maxOutputTokens > m.contextWindow) problems.push(`${model}：最大输出 token 不能超过上下文窗口`)
      if (p.enabled && m.id) {
        if (enabledModels.has(m.id)) problems.push(`${model}：已在启用的${enabledModels.get(m.id)}中使用，启用的接口之间模型 ID 不能重复`)
        else enabledModels.set(m.id, name)
      }
    })
  })
  if (accessKey) {
    if (accessKey.length < 32 || accessKey.length > 512 || /\s/.test(accessKey)) problems.push('内部访问 Key 需为 32–512 位且不含空格')
  } else if (!gateway.hasAccessKey) {
    problems.push('请设置至少 32 位的内部访问 Key')
  }
  return problems
}
