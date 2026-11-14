<script setup>
import { computed, onMounted } from 'vue'
import { IconApps, IconUserGroup, IconSafe, IconSettings, IconRefresh, IconDown } from '@arco-design/web-vue/es/icon'
import { api } from './api'
import { me, page, organization, admin, roleLabel, load, navigate, refresh, logout } from './store'
import { pages } from './pages'
import LoginView from './pages/LoginView.vue'

const current = computed(() => pages[page.value] || pages.members)

onMounted(async () => { try { me.value = await api('/auth/me'); await load() } catch { me.value = null } })
</script>

<template>
<LoginView v-if="!me||me.mustChangePassword"/>
<div v-else class="console">
<header class="header"><div class="brand"><img class="brand-mark" src="/workdsh-mark.svg" alt="WorkDSH"/><strong>WorkDSH</strong><span class="brand-divider"></span><span>企业控制台</span></div><div class="header-right"><a-tag color="blue">{{organization.name}}</a-tag><a-dropdown @select="logout"><button class="account-menu"><a-avatar :size="28">{{me.displayName.slice(0,1)}}</a-avatar>{{me.displayName}}<icon-down/></button><template #content><a-doption value="logout">退出登录</a-doption></template></a-dropdown></div></header>
<aside class="sidebar"><a-menu :selected-keys="[page]" :default-open-keys="['organization','security']" @menu-item-click="navigate"><a-menu-item key="overview"><template #icon><icon-apps/></template>组织概览</a-menu-item><a-sub-menu v-if="admin" key="organization"><template #icon><icon-user-group/></template><template #title>组织与成员</template><a-menu-item key="members">组织架构</a-menu-item><a-menu-item key="roles">角色管理</a-menu-item><a-menu-item key="visibility">可见范围与协作</a-menu-item></a-sub-menu><a-sub-menu v-if="admin" key="security"><template #icon><icon-safe/></template><template #title>安全中心</template><a-menu-item key="audit">操作日志</a-menu-item><a-menu-item key="sessions">成员会话</a-menu-item></a-sub-menu><a-menu-item v-if="admin" key="models"><template #icon><icon-settings/></template>企业模型</a-menu-item><a-menu-item v-if="admin" key="settings"><template #icon><icon-settings/></template>企业设置</a-menu-item></a-menu><div class="sidebar-bottom"><a-avatar :size="32">W</a-avatar><div>{{organization.name}}<small>{{roleLabel(me.role)}}</small></div></div></aside>
<main class="workspace"><div class="page-heading"><div><div class="breadcrumb">企业控制台 / {{current.breadcrumb||current.title}}</div><h1>{{current.title}}</h1></div><a-button @click="refresh"><template #icon><icon-refresh/></template>刷新</a-button></div>
<a-alert v-if="!admin" type="info">你是普通成员，无权访问组织管理。请在 DSH 中使用企业协作。</a-alert>
<component v-else :is="current.component" :key="page"/>
</main></div>
</template>
