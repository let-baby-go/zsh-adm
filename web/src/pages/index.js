// Page table: key → title and component. The shell renders the selected entry; each page is its own chunk,
// loaded the first time it is opened.
import { defineAsyncComponent } from 'vue'

const lazy = loader => defineAsyncComponent({ loader, delay: 150 })

export const pages = {
  overview: { title: '组织概览', component: lazy(() => import('./OverviewPage.vue')) },
  members: { title: '组织架构', breadcrumb: '组织与成员', component: lazy(() => import('./MembersPage.vue')) },
  roles: { title: '角色管理', component: lazy(() => import('./RolesPage.vue')) },
  visibility: { title: '可见范围与协作', component: lazy(() => import('./VisibilityPage.vue')) },
  audit: { title: '操作日志', component: lazy(() => import('./AuditPage.vue')) },
  sessions: { title: '成员会话', component: lazy(() => import('./SessionsPage.vue')) },
  models: { title: '企业模型', component: lazy(() => import('./ModelsPage.vue')) },
  settings: { title: '企业设置', component: lazy(() => import('./SettingsPage.vue')) },
}
