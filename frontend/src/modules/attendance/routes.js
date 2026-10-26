export default [
  {
    path: 'attendance',
    component: () => import('./AttendancePage.vue'),
    meta: { title: '今日打卡', attendanceView: 'today' },
  },
  {
    path: 'attendance/records',
    component: () => import('./AttendancePage.vue'),
    meta: { title: '考勤记录', attendanceView: 'records' },
  },
  {
    path: 'attendance/rules',
    component: () => import('./AttendancePage.vue'),
    meta: { title: '排班与规则', attendanceView: 'rules' },
  },
  {
    path: 'attendance/face',
    component: () => import('./AttendancePage.vue'),
    meta: { title: '人脸管理', attendanceView: 'face' },
  },
]
