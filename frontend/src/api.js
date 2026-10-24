import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000, withCredentials: true })
http.interceptors.response.use(
  (response) => response.data.data,
  (error) => {
    const url = error.config?.url || ''
    if (error.response?.status === 401 && !url.startsWith('/auth/')) {
      window.dispatchEvent(new Event('session-expired'))
    }
    error.message = error.response?.data?.message || '暂时无法连接服务，请确认后端已启动后重试'
    return Promise.reject(error)
  },
)

export default http

export async function login(username, password) {
  const csrf = await http.get('/auth/csrf')
  return http.post('/auth/login', new URLSearchParams({ username, password }), {
    headers: { [csrf.headerName]: csrf.token },
  })
}

export async function logout() {
  const csrf = await http.get('/auth/csrf')
  return http.post('/auth/logout', null, { headers: { [csrf.headerName]: csrf.token } })
}
