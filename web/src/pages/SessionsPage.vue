<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { run, refreshTick } from '../store'

const PAGE = 50
/** Messages longer than this are folded until expanded, so one long answer does not stall the drawer. */
const FOLD = 2000

const sessions = ref([]), offset = ref(0), loading = ref(false)
const detail = ref(null), messages = ref([]), more = ref(false), next = ref(0), detailLoading = ref(false)
const expanded = reactive(new Set())

const page = () => offset.value / PAGE + 1
const lastPage = () => sessions.value.length < PAGE

async function loadSessions(target = offset.value) {
  loading.value = true
  try {
    const rows = await run(() => api(`/admin/sessions?offset=${target}&limit=${PAGE}`))
    // A page emptied by deletions elsewhere falls back to the previous one.
    if (!rows.length && target > 0) return loadSessions(target - PAGE)
    sessions.value = rows
    offset.value = target
  } catch {} finally { loading.value = false }
}

async function viewSession(record, append = false) {
  detailLoading.value = true
  try {
    const result = await run(() => api('/admin/sessions/' + encodeURIComponent(record.memberId) + '/' + encodeURIComponent(record.sessionId) + '?offset=' + (append ? next.value : 0)))
    if (!append) expanded.clear()
    detail.value = record
    messages.value = append ? [...messages.value, ...result.messages] : result.messages
    next.value = result.nextOffset
    more.value = result.hasMore
  } catch {} finally { detailLoading.value = false }
}

function closeDetail() { detail.value = null; messages.value = []; expanded.clear() }

function messageText(content) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map(part => part.type === 'text' ? part.text : part.type === 'image' ? '[图片]' : part.type === 'file' ? '[附件]' : '').filter(Boolean).join('\n')
  return ''
}

function shown(message) {
  const text = messageText(message.content) || '[非文本内容]'
  return text.length > FOLD && !expanded.has(message.seq) ? text.slice(0, FOLD) + '…' : text
}

const folded = message => messageText(message.content).length > FOLD
function toggle(message) { expanded.has(message.seq) ? expanded.delete(message.seq) : expanded.add(message.seq) }

onMounted(() => loadSessions(0))
watch(refreshTick, () => loadSessions())
</script>

<template>
<section class="content-panel"><h2>成员会话</h2><p class="muted">管理员可只读查看本组织成员的聊天正文，查看会记录操作日志。按创建时间倒序，每页 {{PAGE}} 条。</p>
<a-table :data="sessions" :loading="loading" :pagination="false" :bordered="false"><template #columns><a-table-column title="成员" data-index="memberName"/><a-table-column title="会话 ID" data-index="sessionId" ellipsis/><a-table-column title="消息数" data-index="eventCount" :width="90"/><a-table-column title="创建时间"><template #cell="{record}">{{new Date(record.createdAt).toLocaleString()}}</template></a-table-column><a-table-column title="操作"><template #cell="{record}"><a-button type="text" @click="viewSession(record)">查看正文</a-button></template></a-table-column></template><template #empty><a-empty :description="offset===0?'暂无 Desktop 上报的会话':'这一页没有会话'"/></template></a-table>
<a-space><a-button :disabled="offset===0||loading" @click="loadSessions(offset-PAGE)">上一页</a-button><span class="muted">第 {{page()}} 页</span><a-button :disabled="lastPage()||loading" @click="loadSessions(offset+PAGE)">下一页</a-button></a-space></section>
<a-drawer :visible="!!detail" title="会话正文" :width="720" :footer="false" @cancel="closeDetail"><p class="muted">{{detail?.memberName}} · 只读查看 · 已加载 {{messages.length}} / {{detail?.eventCount}} 条</p><a-spin :loading="detailLoading" style="display:block"><a-empty v-if="!messages.length&&!detailLoading" description="当前记录没有聊天正文"/><article v-for="message in messages" :key="message.seq" style="margin-bottom:24px"><strong>{{message.role==='user'?'用户':'助手'}}</strong><small class="muted"> · {{new Date(message.time).toLocaleString()}}</small><pre style="white-space:pre-wrap;overflow-wrap:anywhere;font:inherit">{{shown(message)}}</pre><a-button v-if="folded(message)" type="text" size="small" @click="toggle(message)">{{expanded.has(message.seq)?'收起':'展开全文（'+messageText(message.content).length+' 字）'}}</a-button></article><a-button v-if="more" :loading="detailLoading" @click="viewSession(detail,true)">加载更多</a-button></a-spin></a-drawer>
</template>
