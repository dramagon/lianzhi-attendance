// A integrates this module into the authenticated layout once the C branch is merged.
// Status-only page is available to all authenticated roles; backend remains authoritative.
export default [
  {
    path: 'knowledge',
    name: 'knowledge',
    component: () => import('./KnowledgeView.vue'),
    meta: { title: '企业知识库' },
  },
]
