<script setup>
import { IconUserGroup, IconSafe, IconFolder } from '@arco-design/web-vue/es/icon'
import { memberSummary, organization, navigate } from '../store'
</script>

<template>
<div class="stats"><section class="stat"><icon-user-group/><span>组织成员</span><strong>{{memberSummary.total}}</strong><small>{{memberSummary.active}} 人已启用</small></section><section class="stat"><icon-folder/><span>组织部门</span><strong>{{organization.departments.length}}</strong><small>按部门管理人员归属</small></section><section class="stat"><icon-safe/><span>管理员</span><strong>{{memberSummary.admins}}</strong><small>含组织所有者</small></section></div><section class="content-panel"><h2>组织管理</h2><p class="muted">成员身份由企业服务统一管理，DSH 通过企业身份插件接入。</p><div class="quick-actions"><a-button @click="navigate('members')">管理组织架构</a-button><a-button @click="navigate('roles')">查看角色权限</a-button><a-button @click="navigate('visibility')">配置协作范围</a-button></div></section>
</template>
