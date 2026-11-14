<script setup>
import { Message } from '@arco-design/web-vue'
import { api } from '../api'
import { policy, owner, run, load } from '../store'

async function savePolicy() {
  try {
    await run(async () => {
      Object.assign(policy, await api('/admin/organization/policy', { method: 'PUT', body: JSON.stringify(policy) }))
      Message.success('权限策略已保存，DSH 同事目录与新分享即时生效')
      await load()
    })
  } catch {}
}
</script>

<template>
<section class="content-panel policy-panel"><h2>通讯录与协作范围</h2><p class="muted">影响 DSH 会话中 @ 同事目录及新的分享请求，由服务端执行。</p><div class="policy-row"><div><h3>同事可见范围</h3><p>选择成员能查找到哪些同事</p></div><a-radio-group v-model="policy.directoryScope" :disabled="!owner"><a-radio value="ORGANIZATION">全组织成员</a-radio><a-radio value="DEPARTMENT">仅同部门成员</a-radio></a-radio-group></div><div class="policy-row"><div><h3>允许新的协作分享</h3><p>关闭后不能创建新分享；已有分享仍仅双方可查看</p></div><a-switch v-model="policy.sharingEnabled" :disabled="!owner"/></div><a-alert>同部门按主部门判断，不包含子部门；未分配部门的成员互相可见。停用成员立即从通讯录移除。</a-alert><div class="policy-footer"><a-button type="primary" :disabled="!owner" @click="savePolicy">保存策略</a-button><span class="muted">仅组织所有者可修改</span></div></section>
</template>
