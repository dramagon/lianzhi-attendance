// Integrated inside the authenticated layout; backend authorizes each business query.
export default [
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
