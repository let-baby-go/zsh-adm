// Used only when the server sent no detail (for example a proxy or filter rejected the request).
export function statusMessage(status) {
  const known = { 401: '登录已过期，请重新登录', 403: '没有权限执行此操作', 404: '请求的内容不存在', 409: '数据已变化，请刷新后重试', 413: '提交的内容过大' }
  if (known[status]) return known[status]
  return status >= 500 ? `服务暂时不可用 (${status})` : `请求失败 (${status})`
}

export async function api(path, options = {}) {
  const isForm = options.body instanceof FormData
  const response = await fetch(`/api${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      ...(options.body && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  })
  if (response.status === 204) return null
  const text = await response.text()
  let payload
  try { payload = text ? JSON.parse(text) : null } catch { payload = null }
  if (!response.ok) {
    const error = new Error(payload?.detail || payload?.message || statusMessage(response.status))
    error.status = response.status
    throw error
  }
  return payload
}

export const post = (path, body) => api(path, { method: 'POST', body: JSON.stringify(body) })
export const patch = (path, body) => api(path, { method: 'PATCH', body: JSON.stringify(body) })

export async function download(path, filename) {
  const response = await fetch(`/api${path}`, {
    credentials: 'same-origin',
  })
  if (!response.ok) throw new Error(`下载失败 (${response.status})`)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
