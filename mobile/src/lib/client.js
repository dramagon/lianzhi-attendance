// Same server Session + per-write CSRF on every platform. Native cookies stay in memory.
export function createClient(
  runtime,
  { base = "", cookieHeader = false, expired = () => {} } = {},
) {
  if (base && !/^https:\/\/[^\s]+$/.test(base))
    throw new Error("服务地址须使用HTTPS");
  base = base.replace(/\/$/, "");
  let cookies = {};
  let sessionVersion = 0;
  function clear() {
    cookies = {};
    sessionVersion++;
  }
  function absorb(response) {
    if (!cookieHeader) return;
    const values = [...(response.cookies || [])];
    const header =
      response.header?.["Set-Cookie"] || response.header?.["set-cookie"];
    if (header && !values.length)
      values.push(...String(header).split(/,(?=\s*(?:JSESSIONID|SESSION)=)/));
    for (const value of values) {
      const pair = String(value).split(";")[0].trim();
      const index = pair.indexOf("=");
      if (index > 0 && /^(JSESSIONID|SESSION)$/.test(pair.slice(0, index)))
        cookies[pair.slice(0, index)] = pair.slice(index + 1);
    }
  }
  async function request(path, { method = "GET", data, headers = {} } = {}) {
    if (!path.startsWith("/") || path.startsWith("//"))
      throw new Error("无效的接口路径");
    const version = sessionVersion;
    const response = await new Promise((resolve, reject) =>
      runtime.request({
        url: `${base}/api${path}`,
        method,
        data,
        withCredentials: true,
        header: {
          "Content-Type": "application/json",
          ...headers,
          ...(cookieHeader && Object.keys(cookies).length
            ? {
                Cookie: Object.entries(cookies)
                  .map(([key, value]) => `${key}=${value}`)
                  .join("; "),
              }
            : {}),
        },
        success: resolve,
        fail: () =>
          reject(
            new Error("网络连接失败；打卡结果未知时请核对回执，不要自动重发"),
          ),
      }),
    );
    if (version !== sessionVersion) throw new Error("会话已切换，已忽略旧响应");
    absorb(response);
    let body = response.data;
    if (typeof body === "string") {
      try {
        body = JSON.parse(body);
      } catch {
        body = null;
      }
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      const error = new Error(
        body?.message || `服务返回${response.statusCode}，请重试`,
      );
      error.status = response.statusCode;
      if (response.statusCode === 401 && path !== "/auth/login") {
        clear();
        expired();
      }
      throw error;
    }
    if (!body || !Object.prototype.hasOwnProperty.call(body, "data"))
      throw new Error("服务响应格式无效");
    return body.data;
  }
  async function write(method, path, data, headers = {}) {
    const csrf = await request("/auth/csrf");
    return request(path, {
      method,
      data,
      headers: { ...headers, [csrf.headerName]: csrf.token },
    });
  }
  async function login(username, password) {
    clear();
    const csrf = await request("/auth/csrf");
    const data = Object.entries({ username, password })
      .map(
        ([key, value]) =>
          `${encodeURIComponent(key)}=${encodeURIComponent(value)}`,
      )
      .join("&");
    return request("/auth/login", {
      method: "POST",
      data,
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [csrf.headerName]: csrf.token,
      },
    });
  }
  return { request, write, login, clear };
}
