import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import attendanceRoutes from './modules/attendance/routes'
import organizationRoutes from './modules/organization/routes'
import approvalRoutes from './modules/approval/routes'
import intelligenceRoutes from './modules/intelligence/routes'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      component: () => import('./layouts/AppLayout.vue'),
      children: [
        ...attendanceRoutes,
        ...organizationRoutes,
        ...approvalRoutes,
        ...intelligenceRoutes,
        { path: '', redirect: '/workbench' },
        {
          path: 'workbench',
          name: 'workbench',
          component: () => import('./views/WorkbenchView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'accounts',
          component: () => import('./views/AccountsView.vue'),
          meta: { title: '系统账号', permission: 'accounts:read' },
        },
        {
          path: 'profile',
          component: () => import('./views/ProfileView.vue'),
          meta: { title: '个人信息', permission: 'profile:read' },
        },
        {
          path: 'roadmap',
          redirect: '/workbench',
        },
        {
          path: 'forbidden',
          component: () => import('./views/StatusView.vue'),
          meta: { title: '访问受限', status: '403' },
        },
        {
          path: ':pathMatch(.*)*',
          component: () => import('./views/StatusView.vue'),
          meta: { title: '页面不存在', status: '404' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.ensureSession()
  if (!to.meta.public && !auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.user) return { name: 'workbench' }
  if (to.meta.permission && !auth.can(to.meta.permission)) return '/forbidden'
})
router.afterEach((to) => {
  document.title = `${to.meta.title || '工作台'} · 联智`
})

export default router
