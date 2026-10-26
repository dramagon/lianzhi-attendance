export default [
  {
    path: 'organization',
    component: () => import('./OrganizationView.vue'),
    meta: { title: '组织与场所', permission: 'organization:manage' },
  },
]
