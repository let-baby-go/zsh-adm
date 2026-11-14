<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { run, refreshTick } from '../store'

const sessions = ref([]), sessionOffset = ref(0), sessionDetail = ref(null), sessionMessages = ref([]), sessionMore = ref(false), sessionNext = ref(0)

async function loadSessions() {
  try { sessions.value = await run(() => api('/admin/sessions?offset=' + sessionOffset.value)) } catch {}
}

async function viewSession(record, more = false) {
  try {
    const result = await run(() => api('/admin/sessions/' + encodeURIComponent(record.memberId) + '/' + encodeURIComponent(record.sessionId) + '?offset=' + (more ? sessionNext.value : 0)))
    sessionDetail.value = record
    sessionMessages.value = more ? [...sessionMessages.value, ...result.messages] : result.messages
    sessionNext.value = result.nextOffset
    sessionMore.value = result.hasMore
  } catch {}
}

function messageText(content) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map(part => part.type === 'text' ? part.text : part.type === 'image' ? '[图片]' : part.type === 'file' ? '[附件]' : '').filter(Boolean).join('\n')
  return ''
}

onMounted(loadSessions)
watch(refreshTick, loadSessions)
</script>

<template>
<section class="content-panel"><h2>成员会话</h2><p class="muted">管理员可只读查看本组织成员的聊天正文，查看会记录操作日志。</p><a-table :data="sessions" :pagination="false" :bordered="false"><template #columns><a-table-column title="成员" data-index="memberName"/><a-table-column title="会话 ID" data-index="sessionId" ellipsis/><a-table-column title="创建时间"><template #cell="{record}">{{new Date(record.createdAt).toLocaleString()}}</template></a-table-column><a-table-column title="操作"><template #cell="{record}"><a-button type="text" @click="viewSession(record)">查看正文</a-button></template></a-table-column></template></a-table><a-space><a-button :disabled="sessionOffset===0" @click="sessionOffset-=50;loadSessions()">上一页</a-button><a-button :disabled="sessions.length&lt;50" @click="sessionOffset+=50;loadSessions()">下一页</a-button></a-space></section>
<a-drawer :visible="!!sessionDetail" title="会话正文" :width="720" :footer="false" @cancel="sessionDetail=null;sessionMessages=[]"><p class="muted">{{sessionDetail?.memberName}} · 只读查看</p><a-empty v-if="!sessionMessages.length" description="当前记录没有聊天正文"/><article v-for="message in sessionMessages" :key="message.seq" style="margin-bottom:24px"><strong>{{message.role==='user'?'用户':'助手'}}</strong><small class="muted"> · {{new Date(message.time).toLocaleString()}}</small><pre style="white-space:pre-wrap;overflow-wrap:anywhere;font:inherit">{{messageText(message.content)||'[非文本内容]'}}</pre></article><a-button v-if="sessionMore" @click="viewSession(sessionDetail,true)">加载更多</a-button></a-drawer>
</template>
