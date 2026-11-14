<script setup>
import { audits, auditOffset, AUDIT_PAGE, loadAudits } from '../store'
import { auditLabel } from '../labels'
</script>

<template>
<section class="content-panel"><h2>组织操作记录</h2><p class="muted">记录组织管理与协作操作，不展示成员私有会话正文。按时间倒序，每页 {{AUDIT_PAGE}} 条。</p><a-table :data="audits" row-key="id" :bordered="false" :pagination="false"><template #columns><a-table-column title="操作时间" :width="170"><template #cell="{record}">{{new Date(record.occurredAt).toLocaleString()}}</template></a-table-column><a-table-column title="操作人" :width="130"><template #cell="{record}">{{record.actorName||'已移除成员'}}</template></a-table-column><a-table-column title="操作"><template #cell="{record}">{{auditLabel(record.action)}}</template></a-table-column><a-table-column title="对象 ID" data-index="targetId" ellipsis/></template></a-table><a-space><a-button :disabled="auditOffset===0" @click="loadAudits(auditOffset-AUDIT_PAGE)">上一页</a-button><a-button :disabled="audits.length&lt;AUDIT_PAGE" @click="loadAudits(auditOffset+AUDIT_PAGE)">下一页</a-button></a-space></section>
</template>
