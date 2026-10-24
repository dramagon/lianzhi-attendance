import { defineStore } from 'pinia'
import http, { login, logout } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null, initialized: false }),
  getters: {
    isEmployee: (state) => state.user?.role === 'EMPLOYEE',
    can: (state) => (permission) => state.user?.permissions.includes(permission) || false,
  },
  actions: {
    async ensureSession() {
      if (this.initialized) return
      try {
        this.user = await http.get('/auth/session')
      } catch {
        this.user = null
      } finally {
        this.initialized = true
      }
    },
    async signIn(username, password) {
      this.user = await login(username, password)
      this.initialized = true
    },
    async signOut() {
      await logout()
      this.discard()
    },
    discard() {
      this.user = null
      this.initialized = true
    },
  },
})
