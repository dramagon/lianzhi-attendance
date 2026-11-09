// Invoked only by scripts/verify-b-c-session.py against freshly owned services.
import assert from "node:assert/strict";
import { createClient } from "../src/lib/client.js";
import { uuid } from "../src/lib/punch.js";
if (process.env.B_ISOLATED_SESSION_TEST !== "1")
  throw new Error(
    "Use the isolated Python runner; never target an existing employee database",
  );
const origins = JSON.parse(process.env.B_SESSION_ORIGINS || "[]");
assert.equal(origins.length, 2);
for (const origin of origins)
  assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/);
let count = 0;
const nodes = [0, 0];
const runtime = {
  request(options) {
    const node = count++ % 2;
    nodes[node]++;
    const url = options.url.replace(
      "https://b-isolated.invalid",
      origins[node],
    );
    let body = options.data;
    if (body && typeof body === "object" && !(body instanceof ArrayBuffer))
      body = JSON.stringify(body);
    fetch(url, {
      method: options.method,
      headers: options.header,
      body: options.method === "GET" ? undefined : body,
    })
      .then(async (response) =>
        options.success({
          statusCode: response.status,
          data: await response.text(),
          cookies: response.headers.getSetCookie(),
        }),
      )
      .catch(() => options.fail());
  },
};
const admin = createClient(runtime, {
  base: "https://b-isolated.invalid",
  cookieHeader: true,
});
assert.equal((await admin.login("admin", "Lianzhi@2026")).role, "ADMIN");
const profile = await admin.request("/employees/me");
const status = await admin.request("/attendance/status");
const date = new Date(Date.parse(status.serverTime) + 8 * 3600000)
  .toISOString()
  .slice(0, 10);
const name = "bc" + Date.now();
await admin.write("POST", "/workplaces", {
  companyId: profile.companyId,
  name,
  latitude: 31.2,
  longitude: 121.5,
  coordinateSystem: "WGS84",
  radiusMeters: 200,
  enabled: true,
});
const place = (await admin.request("/workplaces?page=1&size=100")).items.find(
  (item) => item.name === name,
);
assert.ok(place);
const employee = await admin.write("POST", "/employees", {
  username: name,
  password: "OnlySynthetic@2026",
  displayName: "B/C合成会话员工",
  employeeNo: name,
  role: "EMPLOYEE",
  companyId: profile.companyId,
  departmentId: null,
  enabled: true,
  workplaceIds: [place.id],
});
await admin.write("POST", "/attendance/schedules", {
  userId: employee.userId,
  workplaceId: place.id,
  businessDate: date,
  holiday: false,
  rule: {
    start: "00:00",
    end: "23:59",
    breakStart: null,
    breakEnd: null,
    graceMinutes: 5,
    workdays: [
      "MONDAY",
      "TUESDAY",
      "WEDNESDAY",
      "THURSDAY",
      "FRIDAY",
      "SATURDAY",
      "SUNDAY",
    ],
  },
});
const client = createClient(runtime, {
  base: "https://b-isolated.invalid",
  cookieHeader: true,
});
assert.equal(
  (await client.login(name, "OnlySynthetic@2026")).id,
  employee.userId,
);
assert.equal((await client.request("/auth/session")).id, employee.userId);
const qr = await admin.write(
  "POST",
  `/attendance/workplaces/${place.id}/challenge`,
  {},
);
const requestId = uuid();
const event = await client.write("POST", "/attendance/me/punches", {
  type: "IN",
  token: qr.token,
  requestId,
  location: { latitude: 31.2, longitude: 121.5, coordinateSystem: "WGS84" },
  accuracyMeters: 10,
  faceVerificationId: null,
});
for (let i = 0; i < 2; i++) {
  const receipt = await client.request(
    `/attendance/me/punches/requests/${requestId}`,
  );
  assert.equal(receipt.found, true);
  assert.equal(receipt.event.id, event.id);
}
const records = await client.request(
  `/attendance/me/records?from=${date}&to=${date}`,
);
assert.equal(records.total, 1);
assert.equal(
  (await client.request(`/attendance/me/days?from=${date}&to=${date}`)).length,
  1,
);
await client.write("POST", "/auth/logout");
await assert.rejects(
  client.request("/auth/session"),
  (error) => error.status === 401,
);
assert.ok(nodes.every((value) => value > 0));
console.log(
  JSON.stringify({
    passed: true,
    scope: "B_NATIVE_COOKIE_PROTOCOL_HTTP_ADAPTER_NOT_REAL_WEIXIN",
    alternatingNodes: 2,
    nodeRequestCounts: nodes,
    eventCount: records.total,
    receiptConsistent: true,
    logoutInvalidated: true,
    actualPhone: false,
    actualSdk: false,
  }),
);
