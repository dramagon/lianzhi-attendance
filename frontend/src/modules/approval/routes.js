export default [
  {
    path: 'approvals',
    component: () => import('./ApprovalView.vue'),
    meta: { title: '我的申请', permission: 'approvals:read' },
  },
  {
    path: 'approvals/review',
    component: () => import('./ApprovalView.vue'),
    meta: { title: '审批工作台', permission: 'approvals:manage', approvalReview: true },
  },
]
