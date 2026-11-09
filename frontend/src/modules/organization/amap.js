let pending

// 所有场所弹窗复用同一次加载；失败后可重新请求。
export function loadAMap(key, securityCode) {
  if (window.AMap?.Map && window.AMap?.Marker) return Promise.resolve(window.AMap)
  if (pending) return pending
  if (!key || !securityCode) return Promise.reject(new Error('地图凭据未配置完整'))
  window._AMapSecurityConfig = { securityJsCode: securityCode }
  pending = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    let timer
    function finish(error) {
      clearTimeout(timer)
      script.onload = null
      script.onerror = null
      if (error) {
        script.remove()
        reject(error)
      } else resolve(window.AMap)
    }
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    script.async = true
    script.onload = () =>
      finish(window.AMap?.Map && window.AMap?.Marker ? null : new Error('地图组件未就绪'))
    script.onerror = () => finish(new Error('地图加载失败'))
    timer = setTimeout(() => finish(new Error('地图加载超时')), 12000)
    document.head.appendChild(script)
  }).catch((error) => {
    pending = undefined
    throw error
  })
  return pending
}
