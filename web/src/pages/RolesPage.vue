<script setup>
const permissionRows = [
  { action: '维护部门与普通成员', owner: '允许', admin: '允许', member: '不允许' },
  { action: '任命、变更管理员', owner: '允许', admin: '不允许', member: '不允许' },
  { action: '修改通讯录与分享策略', owner: '允许', admin: '不允许', member: '不允许' },
  { action: '查看组织操作日志', owner: '允许', admin: '允许', member: '不允许' },
  { action: '查找同事与分享资料', owner: '按组织策略', admin: '按组织策略', member: '按组织策略' },
  { action: '只读查看本组织成员会话（记录审计）', owner: '允许', admin: '允许', member: '不允许' },
  { action: '读取协作分享内容', owner: '仅参与双方', admin: '仅参与双方', member: '仅参与双方' },
]
</script>

<template>
<section class="content-panel"><h2>组织角色与权限</h2><p class="muted">当前采用三个固定角色。管理员可只读查看本组织成员会话并记录审计；普通成员只能访问自己的会话。</p><a-table :data="permissionRows" :pagination="false" :bordered="false"><template #columns><a-table-column title="权限项" data-index="action"/><a-table-column title="所有者" data-index="owner"/><a-table-column title="管理员" data-index="admin"/><a-table-column title="普通成员" data-index="member"/></template></a-table><p class="muted">在组织架构中编辑成员角色。只有所有者可以任命或调整管理员。</p></section>
</template>
