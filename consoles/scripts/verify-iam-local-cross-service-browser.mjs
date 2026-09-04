import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import process from "node:process";
import { chromium } from "playwright";

async function readRequiredSecret(name) {
  const file = process.env[name];
  if (!file) {
    throw new Error(`缺少 ${name} 指定的受限凭据文件。`);
  }
  const value = (await readFile(file, "utf8")).trim();
  if (!value) {
    throw new Error(`${name} 指定的受限凭据文件为空。`);
  }
  return value;
}

const email = await readRequiredSecret("SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE");
const password = await readRequiredSecret("SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE");
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  await page.goto("https://platform.saasforge.test/", {
    waitUntil: "domcontentloaded",
  });
  const result = await page.evaluate(async ({ email, password }) => {
    const login = await fetch("https://api.saasforge.test/api/v1/auth/login", {
      body: JSON.stringify({ contextType: "PLATFORM", email, password }),
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-SF-CSRF": "1" },
      method: "POST",
    });
    const loginBody = await login.json();
    if (!login.ok || typeof loginBody.accessToken !== "string") {
      return { loginStatus: login.status, quotaStatus: undefined };
    }
    const quota = await fetch(
      "https://api.saasforge.test/api/v1/platform/quota-definitions",
      {
        body: JSON.stringify({ code: `local-replacement-${crypto.randomUUID()}` }),
        credentials: "include",
        headers: {
          Authorization: `Bearer ${loginBody.accessToken}`,
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
          "X-SF-CSRF": "1",
        },
        method: "POST",
      },
    );
    const quotaBody = await quota.json().catch(() => undefined);
    return {
      loginStatus: login.status,
      quotaCode: quotaBody?.code,
      quotaStatus: quota.status,
    };
  }, { email, password });

  assert.equal(result.loginStatus, 200);
  assert.equal(result.quotaStatus, 201, result.quotaCode);
  assert.deepEqual(consoleErrors, []);
  console.log(
    "BROWSER: HTTPS Edge → Gateway → Entitlement → IAM Platform Role gRPC 返回正式成功结果。",
  );
} finally {
  await browser.close();
}
