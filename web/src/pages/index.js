// Page table: key → title and component. The shell renders the selected entry.
import OverviewPage from './OverviewPage.vue'
import MembersPage from './MembersPage.vue'
import RolesPage from './RolesPage.vue'
import VisibilityPage from './VisibilityPage.vue'
import AuditPage from './AuditPage.vue'
import SessionsPage from './SessionsPage.vue'
import ModelsPage from './ModelsPage.vue'
import SettingsPage from './SettingsPage.vue'

export const pages = {
  overview: { title: '组织概览', component: OverviewPage },
  members: { title: '组织架构', breadcrumb: '组织与成员', component: MembersPage },
  roles: { title: '角色管理', component: RolesPage },
  visibility: { title: '可见范围与协作', component: VisibilityPage },
  audit: { title: '操作日志', component: AuditPage },
  sessions: { title: '成员会话', component: SessionsPage },
  models: { title: '企业模型', component: ModelsPage },
  settings: { title: '企业设置', component: SettingsPage },
}
