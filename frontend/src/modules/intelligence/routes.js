// Integrated inside the authenticated layout; backend authorizes each business query.
export default [
  {
    path: 'reports',
    name: 'reports',
    component: () => import('./ReportsView.vue'),
    meta: { title: '考勤统计报表', permission: 'organization:manage' },
  },
  {
    path: 'assistant',
    name: 'assistant',
    component: () => import('./AssistantView.vue'),
    meta: { title: '业务助手' },
  },
  {
    path: 'knowledge',
    name: 'knowledge',
    component: () => import('./KnowledgeView.vue'),
    meta: { title: '企业知识库' },
  },
]
