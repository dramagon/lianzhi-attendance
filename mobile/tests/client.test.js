import { test } from "node:test";
import assert from "node:assert/strict";
import { createClient } from "../src/lib/client.js";
import {
  validToken,
  freshLocation,
  freshProof,
  readPending,
  savePending,
  multipart,
  uuid,
} from "../src/lib/punch.js";

function runtime(responses) {
  const calls = [];
  return {
    calls,
    request(options) {
      calls.push(options);
      const next = responses.shift();
      if (next === null) options.fail();
      else options.success(next);
    },
    store: new Map(),
    getStorageSync(key) {
      return this.store.get(key);
    },
    setStorageSync(key, value) {
      this.store.set(key, value);
    },
    removeStorageSync(key) {
      this.store.delete(key);
    },
  };
}
const ok = (data, extra = {}) => ({
  statusCode: 200,
  data: { data },
  ...extra,
});
test("native login rotates session cookies in memory and every write uses a fresh CSRF token", async () => {
  const rt = runtime([
    ok(
      { headerName: "X-CSRF", token: "pre" },
      { cookies: ["JSESSIONID=pre-session; Path=/; HttpOnly"] },
    ),
    ok({ id: 3 }, { cookies: ["JSESSIONID=logged-in; Path=/; HttpOnly"] }),
    ok({ headerName: "X-CSRF", token: "fresh" }),
    ok({ id: 77 }),
  ]);
  const api = createClient(rt, {
    base: "https://example.invalid",
    cookieHeader: true,
  });
  await api.login("员工+1", "secret&value");
  await api.write("POST", "/attendance/me/punches", { requestId: uuid() });
  assert.match(rt.calls[1].data, /username=%E5%91%98%E5%B7%A5%2B1/);
  assert.equal(rt.calls[1].header.Cookie, "JSESSIONID=pre-session");
  assert.equal(rt.calls[3].header.Cookie, "JSESSIONID=logged-in");
  assert.equal(rt.calls[3].header["X-CSRF"], "fresh");
  assert.equal(rt.store.size, 0);
});
test("H5 never constructs a forbidden Cookie header; browser manages HttpOnly cookies", async () => {
  const rt = runtime([ok({ id: 3 }, { cookies: ["JSESSIONID=sensitive"] })]);
  const api = createClient(rt);
  await api.request("/auth/session");
  assert.equal(rt.calls[0].withCredentials, true);
  assert.equal(rt.calls[0].header.Cookie, undefined);
});
test("transport errors and 403 do not automatically replay writes", async () => {
  const rt = runtime([ok({ headerName: "X-CSRF", token: "fresh" }), null]);
  const api = createClient(rt);
  await assert.rejects(
    api.write("POST", "/attendance/me/punches", {}),
    /结果未知/,
  );
  assert.equal(rt.calls.length, 2);
  const blocked = runtime([
    ok({ headerName: "X-CSRF", token: "fresh" }),
    { statusCode: 403, data: { message: "权限失效" } },
  ]);
  await assert.rejects(
    createClient(blocked).write("POST", "/attendance/me/punches", {}),
    /权限失效/,
  );
  assert.equal(blocked.calls.length, 2);
});
test("session expiration clears native cookie before another request", async () => {
  let expired = 0;
  const rt = runtime([
    ok({ id: 3 }, { cookies: ["SESSION=old"] }),
    { statusCode: 401, data: { message: "过期" } },
    ok({}),
  ]);
  const api = createClient(rt, {
    base: "https://example.invalid",
    cookieHeader: true,
    expired: () => expired++,
  });
  await api.request("/auth/session");
  await assert.rejects(api.request("/employees/me"), /过期/);
  await api.request("/auth/csrf");
  assert.equal(expired, 1);
  assert.equal(rt.calls[2].header.Cookie, undefined);
});
test("only scoped request identifiers persist, with no image/token/location/session data", () => {
  const rt = runtime([]),
    requestId = uuid();
  savePending(rt, 3, {
    requestId,
    type: "IN",
    token: "secret",
    image: "private",
    location: { latitude: 1 },
  });
  assert.deepEqual(readPending(rt, 3), { userId: 3, requestId, type: "IN" });
  assert.equal(readPending(rt, 4), null);
  savePending(rt, 3, null);
  assert.equal(readPending(rt, 3), null);
});
test("expired proofs, stale/imprecise coordinates and malformed QR data fail closed", () => {
  assert.equal(validToken("not-qr"), false);
  assert.equal(validToken("abc.def"), true);
  assert.equal(
    freshLocation(
      { latitude: 31, longitude: 121, accuracy: 10, timestamp: 1000 },
      62000,
    ),
    false,
  );
  assert.equal(
    freshLocation(
      { latitude: 31, longitude: 121, accuracy: 101, timestamp: 1000 },
      1100,
    ),
    false,
  );
  assert.equal(
    freshProof(
      { verificationId: "id", expiresAt: new Date(1000).toISOString() },
      1000,
    ),
    false,
  );
});
test("multipart transports binary PUT updates without requiring uploadFile POST-only behavior", () => {
  const photo = Uint8Array.from([255, 216, 0, 254]);
  const result = multipart(
    { consent: "true", expectedRevision: "2" },
    photo.buffer,
  );
  const bytes = new Uint8Array(result.data);
  const text = new TextDecoder().decode(bytes);
  assert.match(result.type, /^multipart\/form-data; boundary=Lianzhi/);
  assert.match(text, /name="expectedRevision"\r\n\r\n2\r\n/);
  assert.equal(
    bytes.findIndex(
      (b, i) => b === 255 && bytes[i + 1] === 216 && bytes[i + 2] === 0,
    ),
    text.indexOf("\ufffd"),
  );
  assert.deepEqual(Array.from(photo), [255, 216, 0, 254]);
});

test("late 401 from an older session cannot discard a newer native login", async () => {
  let old,
    expired = 0;
  const replies = [
    ok({ headerName: "X-CSRF", token: "pre" }),
    ok({ id: 3 }, { cookies: ["SESSION=new"] }),
    ok({}),
  ];
  const rt = {
    request(options) {
      if (!old) {
        old = options;
        return;
      }
      options.success(replies.shift());
    },
  };
  const api = createClient(rt, {
    base: "https://example.invalid",
    cookieHeader: true,
    expired: () => expired++,
  });
  const previous = api.request("/auth/session").catch((error) => error);
  await api.login("new-user", "test-only");
  old.success({
    statusCode: 401,
    data: { message: "old session" },
    cookies: ["SESSION=expired"],
  });
  assert.match((await previous).message, /会话已切换/);
  assert.equal(expired, 0);
  await api.request("/auth/csrf");
});

test("C Redis 503 contract keeps native session and never replays a write", async () => {
  let expired = 0;
  const rt = runtime([
    ok({ id: 3 }, { cookies: ["JSESSIONID=current"] }),
    {
      statusCode: 503,
      data: { code: "SESSION_STORE_UNAVAILABLE", message: "会话服务暂不可用" },
    },
    ok({ headerName: "X-CSRF", token: "recovered" }),
    ok({ id: 8 }),
  ]);
  const api = createClient(rt, {
    base: "https://example.invalid",
    cookieHeader: true,
    expired: () => expired++,
  });
  await api.request("/auth/session");
  await assert.rejects(
    api.write("POST", "/attendance/me/punches", {}),
    /会话服务暂不可用/,
  );
  assert.equal(rt.calls.length, 2);
  assert.equal(expired, 0);
  await api.write("POST", "/attendance/me/punches", {});
  assert.equal(rt.calls[3].header.Cookie, "JSESSIONID=current");
  assert.equal(rt.calls[3].header["X-CSRF"], "recovered");
});
