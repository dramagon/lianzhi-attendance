import http from '../../api'

export async function attendancePost(path, data) {
  const csrf = await http.get('/auth/csrf')
  return http.post(path, data, { headers: { [csrf.headerName]: csrf.token } })
}
export const statusLabels = {
  NORMAL: '正常出勤',
  LATE: '迟到',
  EARLY: '早退',
  LATE_AND_EARLY: '迟到且早退',
  REST_DAY: '休息日',
  MISSING_PUNCH: '缺卡',
  ABSENT: '缺勤',
  PENDING: '待结算',
}
export const businessDate = (date = new Date()) => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const part = (type) => parts.find((p) => p.type === type).value
  return `${part('year')}-${part('month')}-${part('day')}`
}
export const localDateTime = (date, time) => (time ? `${date}T${time}:00` : null)
export const timeText = (value) =>
  new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
