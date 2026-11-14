// Display names for every action the server writes to audit_events (AuditLog.record call sites).
const AUDIT_ACTIONS = {
  'member.created': '添加成员',
  'member.updated': '更新成员权限',
  'member.disabled': '停用成员',
  'member.profile.updated': '更新成员资料',
  'department.created': '创建部门',
  'department.updated': '编辑部门',
  'department.deleted': '删除部门',
  'organization.policy.updated': '更新组织策略',
  'session.content.viewed': '查看会话正文',
  'collaboration.handoff.sent': '发起协作分享',
  'collaboration.handoff.message': '回复协作分享',
  'collaboration.handoff.completed': '完成协作分享',
  'organization.model.providers.updated': '更新模型接口',
  'organization.model.provider.key.viewed': '查看供应商 Key',
  'organization.model.gateway.updated': '更新内部访问 Key',
  'organization.model.gateway.key.viewed': '查看内部访问 Key',
}

export const auditActions = Object.keys(AUDIT_ACTIONS)
export const auditLabel = action => AUDIT_ACTIONS[action] || action
