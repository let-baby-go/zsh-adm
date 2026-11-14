<script setup>
import { reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { post } from '../api'
import { me, error, run, load } from '../store'

const busy = ref(false)
const loginForm = reactive({ email: '', password: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '' })

async function login() {
  busy.value = true
  try {
    await run(async () => { me.value = await post('/auth/browser-login', loginForm); loginForm.password = ''; await load() })
  } catch {} finally { busy.value = false }
}

async function changePassword() {
  try {
    await run(() => post('/auth/change-password', passwordForm))
    me.value = null
    Object.assign(passwordForm, { currentPassword: '', newPassword: '' })
    Message.success('密码已设置，请重新登录')
  } catch {}
}
</script>

<template>
<div class="login-wrap"><section class="login-card"><div class="brand"><img class="brand-mark" src="/workdsh-mark.svg" alt="WorkDSH"/><strong>WorkDSH</strong><span class="brand-divider"></span><span>企业控制台</span></div><h1>{{me?'设置登录密码':'登录企业控制台'}}</h1><p class="muted">管理组织成员、部门及协作权限</p><form class="form-stack" @submit.prevent="me?changePassword():login()"><template v-if="!me"><label>账号<a-input v-model="loginForm.email" autocomplete="username" placeholder="邮箱或登录用户名"/></label><label>密码<a-input-password v-model="loginForm.password" autocomplete="current-password" placeholder="输入密码"/></label></template><template v-else><label>临时密码<a-input-password v-model="passwordForm.currentPassword"/></label><label>新密码（至少 12 位）<a-input-password v-model="passwordForm.newPassword"/></label></template><a-alert v-if="error" type="error">{{error}}</a-alert><a-button type="primary" html-type="submit" long :loading="busy">{{me?'设置密码':'登录'}}</a-button></form></section></div>
</template>
