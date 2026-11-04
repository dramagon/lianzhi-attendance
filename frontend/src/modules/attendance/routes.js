export default [
  {
    path: 'attendance/presentation',
    component: () => import('./PresentationPage.vue'),
    meta: { title: '课堂演示台' },
  },
  {
    path: 'attendance/device',
    component: () => import('./DeviceCheck.vue'),
    meta: { title: '设备检查' },
  },
  {
    path: 'attendance/schedules',
    component: () => import('./ScheduleView.vue'),
    meta: { title: '排班与日考勤' },
  },
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
