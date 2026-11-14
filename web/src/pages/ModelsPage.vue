<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { api } from '../api'
import { run, refreshTick, setLeaveGuard } from '../store'
import { modelProblems } from '../modelValidation'

const providerConfig = reactive({ revision: 0, providers: [] })
const gateway = reactive({ hasAccessKey: false }), gatewayKey = ref('')
const revealedKeys = reactive({})
const saving = ref(false)
const problems = ref([])

/** Serialized form as last loaded or saved; differing means unsaved edits. */
const saved = ref('')
const snapshot = () => JSON.stringify([providerConfig.providers, gatewayKey.value])
const dirty = computed(() => saved.value !== '' && snapshot() !== saved.value)

function addProvider() { providerConfig.providers.push({ id: 'provider-' + Date.now(), displayName: '', baseUrl: '', protocol: 'openai-completions', enabled: false, hasKey: false, apiKey: '', models: [] }) }
function addModel(provider) { provider.models.push({ id: '', displayName: '', contextWindow: null, maxOutputTokens: null, images: false }) }
function forgetRevealed() { Object.keys(revealedKeys).forEach(k => delete revealedKeys[k]) }

async function loadModels() {
  forgetRevealed()
  problems.value = []
  try {
    Object.assign(providerConfig, await run(() => api('/admin/model-providers')))
    providerConfig.providers.forEach(p => p.apiKey = '')
    Object.assign(gateway, await api('/admin/model-gateway'))
    gatewayKey.value = ''
    saved.value = snapshot()
  } catch {}
}

async function saveModels() {
  problems.value = modelProblems(providerConfig.providers, gateway, gatewayKey.value)
  if (problems.value.length) { Message.warning(`有 ${problems.value.length} 处需要修改，详见页面底部`); return }
  forgetRevealed()
  saving.value = true
  try {
    Object.assign(providerConfig, await run(() => api('/admin/model-providers', { method: 'PUT', body: JSON.stringify({ expectedRevision: providerConfig.revision, providers: providerConfig.providers, accessKey: gatewayKey.value }) })))
    providerConfig.providers.forEach(p => p.apiKey = '')
    gatewayKey.value = ''
    Object.assign(gateway, await api('/admin/model-gateway'))
    saved.value = snapshot()
    Message.success('接口和成员访问配置已保存')
  } catch {} finally { saving.value = false }
}

/** Revealing a saved key is recorded in the operation log, so it is confirmed first; hiding is immediate. */
function revealKey(provider) {
  const id = provider?.id || '__internal'
  if (revealedKeys[id]) { delete revealedKeys[id]; return }
  Modal.confirm({
    title: provider ? `查看「${provider.displayName || provider.id}」的供应商 Key` : '查看内部访问 Key',
    content: '密钥将以明文显示在页面上，本次查看会记入操作日志。请确认周围无他人且未在共享屏幕。',
    okText: '查看',
    onOk: async () => {
      try {
        const result = await run(() => api(provider ? '/admin/model-providers/' + encodeURIComponent(provider.id) + '/reveal-key' : '/admin/model-gateway/reveal-key', { method: 'POST' }))
        revealedKeys[id] = result.key
      } catch {}
    },
  })
}

function confirmDiscard(proceed) {
  Modal.confirm({ title: '放弃未保存的修改？', content: '企业模型配置有未保存的修改，继续将丢弃这些修改。', okText: '放弃修改', onOk: proceed })
}

const beforeUnload = event => { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }

onMounted(() => {
  loadModels()
  setLeaveGuard(proceed => { if (!dirty.value) return true; confirmDiscard(proceed); return false })
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => { setLeaveGuard(null); window.removeEventListener('beforeunload', beforeUnload) })
watch(refreshTick, () => dirty.value ? confirmDiscard(loadModels) : loadModels())
</script>

<template>
<section class="content-panel model-panel">
<div class="provider-heading"><div><h2>企业模型 API</h2><p class="muted">管理公司模型接口。成员通过 DSH 官方「自定义模型 API」连接内部接口。</p></div><a-button @click="addProvider">添加接口</a-button></div>
<form class="model-form" @submit.prevent="saveModels">
<a-empty v-if="!providerConfig.providers.length" description="尚未配置模型接口，点击添加接口开始"/>
<article v-for="(provider,index) in providerConfig.providers" :key="index" class="provider-card">
<div class="provider-heading"><h3>{{provider.displayName||'新模型接口'}}</h3><a-space><a-switch v-model="provider.enabled" aria-label="启用此接口"/><a-button type="text" status="danger" @click="providerConfig.providers.splice(index,1)">移除接口</a-button></a-space></div>
<div class="provider-grid"><div class="model-field"><span class="field-label">接口 ID</span><a-input v-model="provider.id" :disabled="provider.hasKey" aria-label="接口 ID" placeholder="company-deepseek"/><small class="muted">小写字母开头，支持数字、短横线和下划线。</small></div><div class="model-field"><span class="field-label">显示名称</span><a-input v-model="provider.displayName" aria-label="显示名称" placeholder="公司 DeepSeek"/></div></div>
<div class="model-field"><span class="field-label">上游 API 地址</span><a-input v-model="provider.baseUrl" aria-label="上游 API 地址" placeholder="https://api.deepseek.com"/></div>
<div class="model-field"><span class="field-label">供应商 API Key</span><a-input-password v-model="provider.apiKey" aria-label="供应商 API Key" :placeholder="provider.hasKey?'已配置，留空保留原密钥':'输入供应商 API Key'" autocomplete="new-password"/><div v-if="provider.hasKey"><a-button size="small" @click="revealKey(provider)">{{revealedKeys[provider.id]?'隐藏已保存 Key':'查看已保存 Key'}}</a-button><code v-if="revealedKeys[provider.id]" class="revealed-key">{{revealedKeys[provider.id]}}</code></div></div>
<div class="model-field"><span class="field-label">API 协议</span><a-radio-group v-model="provider.protocol" type="button" aria-label="API 协议"><a-radio value="openai-completions">OpenAI Chat Completions</a-radio><a-radio value="anthropic-messages">Anthropic Messages</a-radio></a-radio-group><small class="muted">选择上游支持的协议；成员配置该模型时选择同一协议。</small></div>
<div class="provider-heading"><h4>模型目录</h4><a-button size="small" @click="addModel(provider)">添加模型</a-button></div>
<p v-if="!provider.models.length" class="muted">启用接口前，请添加至少一个模型。</p>
<div v-for="(model,modelIndex) in provider.models" :key="modelIndex" class="model-catalog-row">
<div class="provider-grid"><div class="model-field"><span class="field-label">模型 ID</span><a-input v-model="model.id" aria-label="模型 ID" placeholder="deepseek-flash"/></div><div class="model-field"><span class="field-label">模型名称</span><a-input v-model="model.displayName" aria-label="模型名称" placeholder="DeepSeek Flash"/></div><div class="model-field"><span class="field-label">上下文窗口（可选）</span><a-input-number v-model="model.contextWindow" :min="1" :max="100000000" aria-label="上下文窗口" placeholder="例如 256000"/></div><div class="model-field"><span class="field-label">最大输出 token（可选）</span><a-input-number v-model="model.maxOutputTokens" :min="1" :max="100000000" aria-label="最大输出 token" placeholder="例如 32000"/></div></div>
<div class="provider-heading"><a-checkbox v-model="model.images">支持图片输入</a-checkbox><a-button type="text" status="danger" @click="provider.models.splice(modelIndex,1)">移除模型</a-button></div></div>
<small class="muted">已启用接口的模型 ID 不可重复；目录参数用于描述模型能力。</small>
</article>
<div class="model-section"><h3>成员访问</h3><p class="muted">供应商密钥只在服务器使用。内部 Key 供成员调用，不能登录管理后台。</p><div class="model-field"><span class="field-label">内部访问 Key</span><a-input-password v-model="gatewayKey" aria-label="内部访问 Key" :placeholder="gateway.hasAccessKey?'已配置，留空保留内部 Key':'设置至少 32 位的内部访问 Key'" autocomplete="new-password"/><div v-if="gateway.hasAccessKey"><a-button size="small" @click="revealKey()">{{revealedKeys.__internal?'隐藏已保存 Key':'查看已保存 Key'}}</a-button><code v-if="revealedKeys.__internal" class="revealed-key">{{revealedKeys.__internal}}</code></div><small class="muted">更换后旧 Key 立即失效。</small></div><div class="model-endpoint"><span>内部 API 路径</span><code>/api/model-gateway/v1</code></div><small class="muted">在此路径前加服务器对成员开放的地址。成员可获取模型目录，再选择相应接口协议。</small></div>
<a-alert v-if="problems.length" type="warning" title="保存前请修改以下问题"><ul style="margin:4px 0 0;padding-left:18px"><li v-for="problem in problems" :key="problem">{{problem}}</li></ul></a-alert>
<div class="model-actions"><a-button type="primary" html-type="submit" :loading="saving">保存配置</a-button><span v-if="dirty" class="muted" style="margin-left:12px">有未保存的修改</span></div>
</form></section>
</template>
