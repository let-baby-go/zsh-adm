<script setup>
import { me, organization, roleLabel } from '../store'
</script>

<template>
<section class="content-panel"><h2>企业信息</h2><a-descriptions :column="1" bordered><a-descriptions-item label="企业名称">{{organization.name}}</a-descriptions-item><a-descriptions-item label="当前账号">{{me.email}}</a-descriptions-item><a-descriptions-item label="组织角色">{{roleLabel(me.role)}}</a-descriptions-item><a-descriptions-item label="登录方式">邮箱 / 管理员设置的登录用户名</a-descriptions-item><a-descriptions-item label="运行端">通过外置企业身份与协作插件接入 DSH</a-descriptions-item></a-descriptions></section>
</template>
