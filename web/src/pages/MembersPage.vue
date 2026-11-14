<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconFolder, IconPlus, IconDown } from '@arco-design/web-vue/es/icon'
import { api, post, patch } from '../api'
import { organization, owner, profile, departmentName, deptOptions, roleLabel, run, load, refreshTick } from '../store'

const department = ref('all'), search = ref(''), statusFilter = ref('all'), recursive = ref(true), selected = ref([])
// Current server page; filters and paging run in /admin/members/search.
const rows = ref([]), total = ref(0), current = ref(1), pageSize = ref(10), loading = ref(false)
const drawer = ref(false), editing = ref(null), saving = ref(false), secret = ref(''), deptModal = ref(false), deptEditing = ref(null)
const moveModal = ref(false), moveDepartment = ref('')
const form = reactive({ displayName: '', email: '', role: 'MEMBER', departmentId: '', username: '', employeeNumber: '', jobTitle: '' })
const deptForm = reactive({ name: '', parentId: '', expectedRevision: 0 })

function tree(parent = null) { return organization.value.departments.filter(d => (d.parentId || null) === parent).map(d => ({ key: d.id, title: d.name, children: tree(d.id) })) }
const treeData = computed(() => [{ key: 'all', title: organization.value.name, children: [...tree(), { key: 'unassigned', title: '未分配部门' }] }])
function descendants(id) { return [id, ...organization.value.departments.filter(d => d.parentId === id).flatMap(d => descendants(d.id))] }
const currentDepartment = computed(() => organization.value.departments.find(d => d.id === department.value))

function query() {
  const params = new URLSearchParams({ q: search.value.trim(), status: statusFilter.value, offset: (current.value - 1) * pageSize.value, limit: pageSize.value })
  if (department.value === 'unassigned') params.set('unassigned', 'true')
  else if (department.value !== 'all') {
    const ids = recursive.value ? descendants(department.value) : [department.value]
    ids.forEach(id => params.append('departments', id))
  }
  return params
}

let latest = 0
async function reloadMembers() {
  const ticket = ++latest
  loading.value = true
  try {
    const page = await run(() => api('/admin/members/search?' + query()))
    if (ticket !== latest) return
    rows.value = page.items
    total.value = page.total
    if (!page.items.length && current.value > 1 && page.total > 0) { current.value = Math.ceil(page.total / pageSize.value); return reloadMembers() }
  } catch {} finally { if (ticket === latest) loading.value = false }
}

/** After a change: refresh this page and the console-wide data (summary, departments, audit). */
async function reloadAll() { await load(); await reloadMembers() }

function firstPage() { selected.value = []; current.value = 1; reloadMembers() }
let searchTimer
watch(search, () => { clearTimeout(searchTimer); searchTimer = setTimeout(firstPage, 300) })
watch([statusFilter, department, recursive], firstPage)
watch(refreshTick, reloadMembers)
onMounted(reloadMembers)
function changePage(page) { selected.value = []; current.value = page; reloadMembers() }
function changePageSize(size) { selected.value = []; pageSize.value = size; current.value = 1; reloadMembers() }
const canEdit = m => m.role !== 'OWNER' && (owner.value || m.role === 'MEMBER')

function openMember(m = null) {
  editing.value = m
  Object.assign(form, { displayName: m?.displayName || '', email: m?.email || '', role: m?.role || 'MEMBER', departmentId: profile(m?.id).departmentId || (department.value !== 'all' && department.value !== 'unassigned' ? department.value : ''), username: profile(m?.id).username || '', employeeNumber: profile(m?.id).employeeNumber || '', jobTitle: profile(m?.id).jobTitle || '' })
  drawer.value = true
}

async function saveMember() {
  if (!form.displayName.trim() || !form.email.trim()) return Message.warning('请填写姓名和邮箱')
  if (form.username && (!/^[a-z0-9_.-]{2,64}$/i.test(form.username) || ['a', 'b', 'admin'].includes(form.username.toLowerCase()))) return Message.warning('登录用户名为 2–64 位字母、数字、点、下划线或短横线')
  saving.value = true
  try {
    await run(async () => {
      let m = editing.value
      if (!m) {
        const result = await post('/admin/members', { email: form.email, displayName: form.displayName, role: form.role })
        m = result.member; editing.value = m; secret.value = result.temporaryPassword
      } else if (m.role !== form.role) {
        m = await patch(`/admin/members/${m.id}`, { role: form.role, expectedRevision: m.revision }); editing.value = m
      }
      await api(`/admin/organization/profiles/${m.id}`, { method: 'PUT', body: JSON.stringify({ ...form, expectedRevision: m.revision }) })
      drawer.value = false
      await reloadAll()
      Message.success('成员资料已保存')
    })
  } catch {} finally { saving.value = false }
}

async function toggle(m) {
  Modal.confirm({
    title: m.active ? '停用成员' : '启用成员',
    content: m.active ? '停用后，成员的登录会话立即失效，Desktop 企业连接需重新登录。' : '允许该成员重新登录。',
    onOk: async () => { await run(() => patch(`/admin/members/${m.id}`, { active: !m.active, expectedRevision: m.revision })); await reloadAll() },
  })
}

/**
 * Applies one change per member, continuing past failures, then reports what happened. Members are updated one
 * by one because each carries its own revision.
 */
const progress = ref('')
async function eachMember(targets, label, change) {
  const failed = []
  for (const [index, m] of targets.entries()) {
    progress.value = `${label}中 ${index + 1}/${targets.length}`
    try { await change(m) } catch (e) { failed.push(`${m.displayName}（${e.message}）`) }
  }
  progress.value = ''
  selected.value = []
  await reloadAll()
  const done = targets.length - failed.length
  if (!failed.length) Message.success(`${label}完成：${done} 位成员`)
  else Modal.warning({ title: `${label}：成功 ${done} 位，失败 ${failed.length} 位`, content: '失败的成员：' + failed.join('；') + '。刷新后可重试。' })
}

async function batch(active) {
  const targets = rows.value.filter(m => selected.value.includes(m.id))
  if (targets.some(m => !canEdit(m))) return Message.warning('所选包含无权管理的成员')
  Modal.confirm({
    title: `批量${active ? '启用' : '停用'} ${targets.length} 位成员`,
    content: active ? '允许成员重新登录。' : '成员的登录会话将立即失效。',
    onOk: () => eachMember(targets, active ? '批量启用' : '批量停用', m => patch(`/admin/members/${m.id}`, { active, expectedRevision: m.revision })),
  })
}

async function moveMembers() {
  const targets = rows.value.filter(x => selected.value.includes(x.id))
  if (targets.some(m => !canEdit(m))) { Message.warning('所选包含无权调整的成员'); return }
  moveModal.value = false
  await eachMember(targets, '调整部门', m => api(`/admin/organization/profiles/${m.id}`, { method: 'PUT', body: JSON.stringify({ ...profile(m.id), displayName: m.displayName, departmentId: moveDepartment.value, expectedRevision: m.revision }) }))
}

function openDepartment(d = null, parent = '') {
  deptEditing.value = d
  Object.assign(deptForm, { name: d?.name || '', parentId: d?.parentId || parent, expectedRevision: d?.revision || 0 })
  deptModal.value = true
}

async function saveDepartment() {
  try {
    await run(async () => {
      if (deptEditing.value) await patch(`/admin/organization/departments/${deptEditing.value.id}`, deptForm)
      else await post('/admin/organization/departments', deptForm)
      deptModal.value = false
      await reloadAll()
      Message.success('部门已保存')
    })
  } catch {}
}

function deleteDepartment() {
  if (!currentDepartment.value) return
  const d = currentDepartment.value
  Modal.confirm({
    title: `删除部门“${d.name}”`,
    content: '只有没有成员和子部门的空部门可以删除。',
    onOk: async () => { await run(() => api(`/admin/organization/departments/${d.id}`, { method: 'DELETE' })); department.value = 'all'; await reloadAll() },
  })
}
</script>

<template>
<section class="organization-layout"><aside class="department-pane"><div class="pane-title"><strong>部门</strong><a-button type="text" size="small" @click="openDepartment()"><template #icon><icon-plus/></template>添加</a-button></div><a-input-search v-model="search" placeholder="搜索人员 / 登录用户名" allow-clear/><div class="department-tree"><a-tree :data="treeData" :selected-keys="[department]" :default-expand-all="true" block-node @select="keys=>{department=keys[0]||'all';selected=[]}"><template #icon><icon-folder/></template></a-tree></div><div v-if="currentDepartment" class="department-actions"><a-button size="small" @click="openDepartment(null,currentDepartment.id)">添加子部门</a-button><a-button size="small" @click="openDepartment(currentDepartment)">编辑</a-button><a-button size="small" status="danger" @click="deleteDepartment">删除</a-button></div><p class="department-footnote">{{organization.departments.length}} 个部门 · 最多 10 层</p></aside>
<div class="member-pane"><div class="member-heading"><h2>{{department==='all'?organization.name:department==='unassigned'?'未分配部门':currentDepartment?.name}}</h2><span class="muted">成员 {{total}} 人</span><span class="selection-count" v-if="selected.length">已选 {{selected.length}} 人</span><span class="selection-count" v-if="progress">{{progress}}</span></div><div class="table-toolbar"><div class="toolbar-filters"><a-select v-model="statusFilter" style="width:116px"><a-option value="all">全部状态</a-option><a-option value="active">已启用</a-option><a-option value="disabled">已停用</a-option></a-select><a-checkbox v-if="currentDepartment" v-model="recursive">包含子部门</a-checkbox></div><div class="toolbar-actions"><a-dropdown :disabled="!selected.length" @select="key=>key==='move'?moveModal=true:batch(key==='enable')"><a-button :disabled="!selected.length">批量操作 <icon-down/></a-button><template #content><a-doption value="move">调整部门</a-doption><a-doption value="enable">批量启用</a-doption><a-doption value="disable">批量停用</a-doption></template></a-dropdown><a-button type="primary" @click="openMember()"><template #icon><icon-plus/></template>添加成员</a-button></div></div>
<a-table :data="rows" :loading="loading" row-key="id" v-model:selected-keys="selected" :row-selection="{type:'checkbox',showCheckedAll:true}" :pagination="{current,pageSize,total,showTotal:true,showPageSize:true}" @page-change="changePage" @page-size-change="changePageSize" :bordered="false" :scroll="{x:840}" size="medium" stripe><template #columns><a-table-column title="姓名" :width="190"><template #cell="{record}"><div class="member-cell"><a-avatar :size="32" :class="record.active?'':'inactive-avatar'">{{record.displayName.slice(0,1)}}</a-avatar><div><strong>{{record.displayName}}</strong><small>{{profile(record.id).jobTitle||'—'}}</small></div><a-tag v-if="record.role==='OWNER'" size="small">所有者</a-tag></div></template></a-table-column><a-table-column title="部门" :width="110"><template #cell="{record}">{{departmentName(profile(record.id).departmentId)}}</template></a-table-column><a-table-column title="登录账号" :width="190"><template #cell="{record}"><div>{{profile(record.id).username||record.email}}</div><small class="muted" v-if="profile(record.id).username">{{record.email}}</small></template></a-table-column><a-table-column title="角色" :width="85"><template #cell="{record}">{{roleLabel(record.role)}}</template></a-table-column><a-table-column title="状态" :width="85"><template #cell="{record}"><span class="status-dot" :class="{disabled:!record.active}"></span>{{record.active?'已启用':'已停用'}}</template></a-table-column><a-table-column title="操作" :width="140" fixed="right"><template #cell="{record}"><a-button type="text" size="small" :disabled="!canEdit(record)" @click="openMember(record)">编辑</a-button><a-dropdown @select="()=>toggle(record)"><a-button type="text" size="small" :disabled="!canEdit(record)">更多<icon-down/></a-button><template #content><a-doption value="toggle">{{record.active?'停用成员':'启用成员'}}</a-doption></template></a-dropdown></template></a-table-column></template><template #empty><a-empty description="暂无符合条件的成员"/></template></a-table>
</div></section>
<a-drawer v-model:visible="drawer" :title="editing?'编辑成员':'添加成员'" :width="520" :footer="true" :unmount-on-close="true"><div class="drawer-section-title">基本信息</div><a-form :model="form" layout="vertical"><a-form-item label="姓名" required><a-input v-model="form.displayName" :max-length="64" placeholder="输入成员姓名"/></a-form-item><a-form-item label="主部门"><a-select v-model="form.departmentId" :options="deptOptions" allow-clear placeholder="选择主部门"/></a-form-item><a-form-item label="邮箱" required><a-input v-model="form.email" :disabled="!!editing" placeholder="name@company.com"/></a-form-item><a-form-item label="登录用户名" help="由管理员设置，2–64 位字母、数字、点、下划线或短横线"><a-input v-model="form.username" placeholder="例如 zhangsan"/></a-form-item><div class="drawer-section-title">组织与岗位</div><a-form-item label="组织角色"><a-select v-model="form.role" :disabled="!owner"><a-option value="MEMBER">成员</a-option><a-option value="ADMIN">管理员</a-option></a-select></a-form-item><a-form-item label="工号"><a-input v-model="form.employeeNumber" :max-length="64"/></a-form-item><a-form-item label="职位"><a-input v-model="form.jobTitle" :max-length="64"/></a-form-item></a-form><a-alert v-if="!editing">直接添加后生成一次性临时密码，成员首次登录须修改。当前不发送邀请邮件。</a-alert><template #footer><a-space><a-button @click="drawer=false">取消</a-button><a-button type="primary" :loading="saving" @click="saveMember">保存</a-button></a-space></template></a-drawer>
<a-modal v-model:visible="deptModal" :title="deptEditing?'编辑部门':'添加部门'" @before-ok="async()=>{await saveDepartment();return !deptModal}"><a-form :model="deptForm" layout="vertical"><a-form-item label="部门名称" required><a-input v-model="deptForm.name" :max-length="64"/></a-form-item><a-form-item label="上级部门"><a-select v-model="deptForm.parentId" :options="deptOptions.filter(d=>d.value!==deptEditing?.id)" allow-clear placeholder="不选则为一级部门"/></a-form-item></a-form></a-modal>
<a-modal v-model:visible="moveModal" title="批量调整部门" @before-ok="async()=>{await moveMembers();return !moveModal}"><p>调整所选 {{selected.length}} 位成员的主部门。</p><a-select v-model="moveDepartment" :options="deptOptions" allow-clear placeholder="选择部门（清空为未分配）"/></a-modal>
<a-modal :visible="!!secret" title="成员临时密码" :footer="false" @cancel="secret=''"><p>请通过安全渠道交给成员。关闭后不会再次显示。</p><code class="credential">{{secret}}</code><a-button type="primary" @click="secret=''">我已记录</a-button></a-modal>
</template>
