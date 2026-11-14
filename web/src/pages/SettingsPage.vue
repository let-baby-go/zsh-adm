<script setup>
import { ref, watch } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { api } from '../api'
import { me, organization, policy, owner, roleLabel, run, load } from '../store'

const retentionDays = ref(policy.bodyRetentionDays || 0)
watch(() => policy.bodyRetentionDays, value => { retentionDays.value = value || 0 })
const saving = ref(false)

async function save() {
  saving.value = true
  try {
    await run(async () => {
      Object.assign(policy, await api('/admin/organization/policy', { method: 'PUT', body: JSON.stringify({ ...policy, bodyRetentionDays: retentionDays.value || 0 }) }))
      Message.success('正文保留期限已保存')
      await load()
    })
  } catch {} finally { saving.value = false }
}

function confirmSave() {
  const days = retentionDays.value || 0
  const shorter = days > 0 && (!policy.bodyRetentionDays || days < policy.bodyRetentionDays)
  if (!shorter) return save()
  Modal.confirm({
    title: `正文只保留 ${days} 天`,
    content: `最近一次上报早于 ${days} 天的会话，正文将在下一次每日清理（北京时间 03:30）时删除，删除后不可恢复。`,
    onOk: save,
  })
}
</script>

<template>
<section class="content-panel"><h2>企业信息</h2><a-descriptions :column="1" bordered><a-descriptions-item label="企业名称">{{organization.name}}</a-descriptions-item><a-descriptions-item label="当前账号">{{me.email}}</a-descriptions-item><a-descriptions-item label="组织角色">{{roleLabel(me.role)}}</a-descriptions-item><a-descriptions-item label="登录方式">邮箱 / 管理员设置的登录用户名</a-descriptions-item><a-descriptions-item label="运行端">通过外置企业身份与协作插件接入 DSH</a-descriptions-item></a-descriptions></section>
<section class="content-panel policy-panel"><h2>会话正文保留</h2><p class="muted">Desktop 上报的成员会话正文保留多久。按会话最近一次上报时间计算，到期后删除正文，会话记录保留为已删除。</p><div class="policy-row"><div><h3>保留天数</h3><p>0 表示永久保留，最多 3650 天</p></div><a-input-number v-model="retentionDays" :min="0" :max="3650" :precision="0" :disabled="!owner" aria-label="正文保留天数" style="width:160px"><template #suffix>天</template></a-input-number></div><div class="policy-footer"><a-button type="primary" :disabled="!owner" :loading="saving" @click="confirmSave">保存</a-button><span class="muted">仅组织所有者可修改；每日北京时间 03:30 清理到期正文</span></div></section>
</template>
