export function uuid() {
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues)
    globalThis.crypto.getRandomValues(bytes);
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const h = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
export const validToken = (value) =>
  typeof value === "string" &&
  value.length <= 512 &&
  /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value);
export const freshLocation = (value, now = Date.now()) =>
  value &&
  Number.isFinite(value.latitude) &&
  Number.isFinite(value.longitude) &&
  value.latitude >= -90 &&
  value.latitude <= 90 &&
  value.longitude >= -180 &&
  value.longitude <= 180 &&
  Number.isFinite(value.accuracy) &&
  value.accuracy > 0 &&
  value.accuracy <= 100 &&
  now >= value.timestamp &&
  now - value.timestamp < 60000;
export const freshProof = (value, now = Date.now()) =>
  !!value?.verificationId && Date.parse(value.expiresAt) > now;
export const validRequestId = (value) =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export function readPending(runtime, userId) {
  try {
    const value = runtime.getStorageSync(`lianzhi.mobile.pending.${userId}`);
    if (
      value?.userId === userId &&
      validRequestId(value.requestId) &&
      ["IN", "OUT"].includes(value.type)
    )
      return value;
  } catch {
    /* Disabled storage must not block current-page receipt recovery. */
  }
  return null;
}
export function savePending(runtime, userId, value) {
  try {
    const key = `lianzhi.mobile.pending.${userId}`;
    if (value)
      runtime.setStorageSync(key, {
        userId,
        requestId: value.requestId,
        type: value.type,
      });
    else runtime.removeStorageSync(key);
  } catch {
    /* The caller keeps the receipt identifier in memory. */
  }
}
export function multipart(fields, bytes, type = "image/jpeg") {
  const boundary = `Lianzhi${uuid().replace(/-/g, "")}`;
  const encode = (text) =>
    Uint8Array.from(Array.from(text, (char) => char.charCodeAt(0)));
  const chunks = Object.entries(fields).map(([key, value]) =>
    encode(
      `--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`,
    ),
  );
  chunks.push(
    encode(
      `--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="capture.jpg"\r\nContent-Type: ${type}\r\n\r\n`,
    ),
    new Uint8Array(bytes),
    encode(`\r\n--${boundary}--\r\n`),
  );
  const data = new Uint8Array(
    chunks.reduce((sum, part) => sum + part.length, 0),
  );
  let offset = 0;
  for (const chunk of chunks) {
    data.set(chunk, offset);
    offset += chunk.length;
  }
  return {
    data: data.buffer,
    type: `multipart/form-data; boundary=${boundary}`,
  };
}
