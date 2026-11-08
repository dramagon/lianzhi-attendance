import { test, expect } from "@playwright/test";
import QRCode from "qrcode";

async function post(request, path, data) {
  const csrf = (await (await request.get("/api/auth/csrf")).json()).data;
  const response = await request.post("/api" + path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()).data;
}
async function fixture(request) {
  const csrf = (await (await request.get("/api/auth/csrf")).json()).data;
  expect(
    (
      await request.post("/api/auth/login", {
        form: { username: "admin", password: "Lianzhi@2026" },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).ok(),
  ).toBe(true);
  const companyId = (await (await request.get("/api/employees/me")).json()).data
    .companyId;
  const username = `mobile${Date.now()}`;
  await post(request, "/workplaces", {
    companyId,
    name: username,
    latitude: 31.2,
    longitude: 121.5,
    coordinateSystem: "WGS84",
    radiusMeters: 200,
    enabled: true,
  });
  const places = (
    await (await request.get("/api/workplaces?page=1&size=100")).json()
  ).data;
  const place = places.items.find((item) => item.name === username);
  expect(place).toBeTruthy();
  const person = await post(request, "/employees", {
    username,
    password: "Test@2026",
    displayName: "移动端合成员工",
    employeeNo: username,
    role: "EMPLOYEE",
    companyId,
    departmentId: null,
    enabled: true,
    workplaceIds: [place.id],
  });
  const status = (await (await request.get("/api/attendance/status")).json())
    .data;
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(status.serverTime));
  const date = ["year", "month", "day"]
    .map((type) => parts.find((part) => part.type === type).value)
    .join("-");
  await post(request, "/attendance/schedules", {
    userId: person.userId,
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
  return { username, place, date, userId: person.userId };
}
async function login(page, username) {
  await page.goto("/");
  await page.locator("#mobile-account input").fill(username);
  await page.locator("#mobile-password input").fill("Test@2026");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByText("上下班打卡", { exact: true })).toBeVisible();
}
async function qr(page, request, place) {
  const challenge = await post(
    request,
    `/attendance/workplaces/${place.id}/challenge`,
    {},
  );
  const image = await QRCode.toBuffer(challenge.token, {
    width: 400,
    margin: 3,
  });
  const chosen = page.waitForEvent("filechooser");
  await page
    .getByRole("button", { name: "扫码或识别二维码", exact: true })
    .click();
  await (
    await chosen
  ).setFiles({ name: "qr.png", mimeType: "image/png", buffer: image });
  await expect(
    page.getByText("已识别二维码；仍须由服务端校验签名、时效和场所", {
      exact: true,
    }),
  ).toBeVisible();
}
async function locate(page, context) {
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({
    latitude: 31.2,
    longitude: 121.5,
    accuracy: 10,
  });
  await page.getByRole("button", { name: "获取本次定位", exact: true }).click();
  await expect(page.getByText(/定位已就绪，精度约10米/)).toBeVisible();
}
async function confirm(page) {
  await page.getByRole("button", { name: "确认打卡", exact: true }).click();
  await page.getByText("确认", { exact: true }).click();
}

test("uni-app H5真实登录、二维码图片解码、定位、上下班及本人日记录", async ({
  page,
  context,
  request,
}) => {
  const data = await fixture(request);
  await login(page, data.username);
  await qr(page, request, data.place);
  await locate(page, context);
  await confirm(page);
  await expect(page.getByText(/服务器已确认打卡/)).toBeVisible();
  await page.getByRole("button", { name: "下班", exact: true }).click();
  await qr(page, request, data.place);
  await confirm(page);
  await expect(page.getByText(/服务器已确认打卡/)).toBeVisible();
  await page.getByRole("button", { name: "本人记录", exact: true }).click();
  await expect(
    page.getByText("原始事件 · 共2条", { exact: true }),
  ).toBeVisible();
  expect(
    (
      await (
        await page.request.get(
          `/api/attendance/me/records?from=${data.date}&to=${data.date}`,
        )
      ).json()
    ).data.total,
  ).toBe(2);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "../artifacts/b-mobile-h5-success.png",
    fullPage: true,
  });
  await page.reload();
  await expect(page.getByText("上下班打卡", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "人脸登记", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "拍摄登记图像", exact: true }),
  ).toBeDisabled();
});

test("写入成功但响应丢失，刷新后核对原回执且不重发", async ({
  page,
  context,
  request,
  browser,
}) => {
  const data = await fixture(request);
  await login(page, data.username);
  await qr(page, request, data.place);
  await locate(page, context);
  let writes = 0;
  let writtenId = "";
  await page.route("**/api/attendance/me/punches", async (route) => {
    writes++;
    writtenId = route.request().postDataJSON().requestId;
    const response = await route.fetch();
    expect(response.ok()).toBe(true);
    await route.abort("failed");
  });
  await confirm(page);
  await expect(
    page.getByText("有一笔打卡待核对", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("网络连接失败");
  const saved = await page.evaluate(() =>
    Object.keys(localStorage)
      .filter((key) => key.includes("lianzhi.mobile.pending"))
      .map((key) => localStorage.getItem(key))
      .join(""),
  );
  expect(saved).not.toMatch(/token|latitude|longitude|password|image|cookie/i);
  const otherContext = await browser.newContext({
    baseURL: new URL(page.url()).origin,
    viewport: { width: 390, height: 844 },
  });
  const other = await otherContext.newPage();
  other.on("request", (req) => {
    if (new URL(req.url()).pathname === "/api/attendance/me/punches") writes++;
  });
  await login(other, data.username);
  await other.locator("#mobile-receipt input").fill(writtenId);
  await other
    .getByRole("button", { name: "核对其他端请求", exact: true })
    .click();
  await expect(other.getByText(/已核对其他端原始记录#/)).toBeVisible();
  await otherContext.close();
  await page.reload();
  await expect(
    page.getByText("有一笔打卡待核对", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "核对原始回执", exact: true }).click();
  await expect(page.getByText(/已从服务器核对记录#/)).toBeVisible();
  expect(writes).toBe(1);
  expect(
    (
      await (
        await page.request.get(
          `/api/attendance/me/records?from=${data.date}&to=${data.date}`,
        )
      ).json()
    ).data.total,
  ).toBe(1);
});

test("定位拒绝可以重试，未就绪时不能打卡或伪装人脸登记", async ({
  page,
  context,
  request,
}) => {
  const data = await fixture(request);
  await login(page, data.username);
  await page.evaluate(() => {
    navigator.geolocation.getCurrentPosition = (_ok, fail) => fail({ code: 1 });
  });
  await page.getByRole("button", { name: "获取本次定位", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("定位失败或权限被拒绝");
  await expect(
    page.getByRole("button", { name: "确认打卡", exact: true }),
  ).toBeDisabled();
  await page.reload();
  await expect(page.getByText("上下班打卡", { exact: true })).toBeVisible();
  await locate(page, context);
  await expect(
    page.getByRole("button", { name: "确认打卡", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "人脸登记", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "确认保存本人登记", exact: true }),
  ).toBeDisabled();
});

const faceState = (revision) => ({
  available: true,
  detection: true,
  comparison: true,
  liveness: true,
  provider: "TEST_ONLY_BROWSER_CONTRACT",
  message: "仅测试页面契约，不是真实SDK",
  storageAvailable: true,
  enrolled: revision > 0,
  revision,
  punchRequired: false,
  deletionAvailable: revision > 0,
});
async function photograph(page, button) {
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: button, exact: true }).click();
  await (
    await chooser
  ).setFiles({
    name: "synthetic.png",
    mimeType: "image/png",
    buffer: await QRCode.toBuffer("TEST_ONLY_IMAGE_NOT_A_PERSON"),
  });
  await expect(page.getByText(/已取得本次/).first()).toBeVisible();
}
test("登记更新删除发送二进制、CSRF及版本（SDK页面契约替身）", async ({
  page,
  request,
}) => {
  const data = await fixture(request);
  let revision = 0;
  const methods = [];
  await page.route("**/api/face/status", (route) =>
    route.fulfill({ json: { data: faceState(revision) } }),
  );
  await page.route("**/api/face/enrollment*", async (route) => {
    const method = route.request().method();
    methods.push(method);
    expect(
      route.request().headers()["x-xsrf-token"] ||
        route.request().headers()["x-csrf-token"],
    ).toBeTruthy();
    if (method !== "DELETE") {
      expect(route.request().headers()["content-type"]).toContain(
        "multipart/form-data; boundary=Lianzhi",
      );
      const body = route.request().postDataBuffer();
      expect(body.length).toBeGreaterThan(300);
      expect(body.toString()).toContain('name="consent"\r\n\r\ntrue');
      if (method === "PUT")
        expect(body.toString()).toContain('name="expectedRevision"\r\n\r\n1');
      revision++;
    } else {
      expect(
        new URL(route.request().url()).searchParams.get("expectedRevision"),
      ).toBe("2");
      revision = 0;
    }
    await route.fulfill({
      json: { data: { enrolled: revision > 0, revision, updatedAt: null } },
    });
  });
  await login(page, data.username);
  await page.getByRole("button", { name: "人脸登记", exact: true }).click();
  await page.evaluate(() => {
    const original = URL.revokeObjectURL.bind(URL);
    window.__imageReleases = 0;
    URL.revokeObjectURL = (value) => {
      window.__imageReleases++;
      original(value);
    };
  });
  await page.locator("uni-checkbox").click();
  await photograph(page, "拍摄登记图像");
  await page
    .getByRole("button", { name: "确认保存本人登记", exact: true })
    .click();
  await expect(
    page.getByText("登记状态：已登记 · 版本1", { exact: true }),
  ).toBeVisible();
  await photograph(page, "拍摄登记图像");
  await page
    .getByRole("button", { name: "确认更新本人登记", exact: true })
    .click();
  await expect(
    page.getByText("登记状态：已登记 · 版本2", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "删除本人登记", exact: true }).click();
  await page.getByText("确认", { exact: true }).click();
  await expect(
    page.getByText("登记状态：尚未登记", { exact: true }),
  ).toBeVisible();
  expect(methods).toEqual(["POST", "PUT", "DELETE"]);
  expect(
    await page.evaluate(() => window.__imageReleases),
  ).toBeGreaterThanOrEqual(2);
});
test("可选人脸失败或过期不回退普通打卡（SDK页面契约替身）", async ({
  page,
  context,
  request,
}) => {
  const data = await fixture(request);
  let verify = 0,
    writes = 0;
  await page.route("**/api/face/status", (route) =>
    route.fulfill({ json: { data: faceState(1) } }),
  );
  await page.route("**/api/face/challenges", (route) =>
    route.fulfill({
      json: {
        data: {
          id: crypto.randomUUID(),
          expiresAt: new Date(Date.now() + 30000).toISOString(),
        },
      },
    }),
  );
  await page.route("**/api/face/verification", (route) => {
    verify++;
    return route.fulfill({
      json: {
        data: {
          verified: verify > 1,
          verificationId: verify > 1 ? "TEST_ONLY_PROOF" : null,
          expiresAt: new Date(Date.now() + 1200).toISOString(),
          message: "测试替身：活体或身份未通过",
        },
      },
    });
  });
  page.on("request", (req) => {
    if (new URL(req.url()).pathname === "/api/attendance/me/punches") writes++;
  });
  await login(page, data.username);
  await qr(page, request, data.place);
  await locate(page, context);
  await page.locator("uni-switch").click();
  await page.locator("uni-checkbox").click();
  await photograph(page, "拍摄本次人脸");
  await page
    .getByRole("button", { name: "提交本次人脸验证", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "测试替身：活体或身份未通过",
  );
  await expect(
    page.getByRole("button", { name: "确认打卡", exact: true }),
  ).toBeDisabled();
  await photograph(page, "拍摄本次人脸");
  await page
    .getByRole("button", { name: "提交本次人脸验证", exact: true })
    .click();
  await expect(
    page.getByText("服务端验证通过，凭据仍有效", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "确认打卡", exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByRole("button", { name: "确认打卡", exact: true }),
  ).toBeDisabled({ timeout: 5000 });
  expect(writes).toBe(0);
});
