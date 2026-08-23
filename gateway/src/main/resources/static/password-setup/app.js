'use strict';

let setupToken = null;
let idempotencyKey = null;

const form = document.querySelector('#password-form');
const newPassword = document.querySelector('#new-password');
const confirmPassword = document.querySelector('#confirm-password');
const submitButton = document.querySelector('#submit-button');
const status = document.querySelector('#form-status');

function readAndClearToken() {
  const fragment = window.location.hash;
  window.history.replaceState(null, '', window.location.pathname);
  const token = new URLSearchParams(fragment.startsWith('#') ? fragment.slice(1) : fragment).get('token');
  if (token && /^[A-Za-z0-9_-]{43}$/.test(token)) {
    setupToken = token;
    idempotencyKey = uuidV7();
    return;
  }
  disableForm('设置链接无效或不完整，请联系管理员重新发送邮件。');
}

function uuidV7() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let timestamp = Date.now();
  for (let index = 5; index >= 0; index -= 1) {
    bytes[index] = timestamp & 0xff;
    timestamp = Math.floor(timestamp / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0'))
    .map((hex, index) => [4, 6, 8, 10].includes(index) ? `-${hex}` : hex)
    .join('');
}

function disableForm(message) {
  form.hidden = true;
  status.className = 'status error';
  status.textContent = message;
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  status.textContent = '';
  if (!setupToken || !idempotencyKey) {
    disableForm('设置链接无效或已被使用，请联系管理员重新发送邮件。');
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    status.className = 'status error';
    status.textContent = '两次输入的密码不一致。';
    confirmPassword.focus();
    return;
  }
  if (!form.reportValidity()) {
    return;
  }

  submitButton.disabled = true;
  submitButton.textContent = '正在设置…';
  try {
    const response = await fetch('/api/v1/auth/password-setups', {
      method: 'POST',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        'X-SF-CSRF': '1'
      },
      body: JSON.stringify({ token: setupToken, newPassword: newPassword.value })
    });
    newPassword.value = '';
    confirmPassword.value = '';
    if (!response.ok) {
      status.className = 'status error';
      status.textContent = response.status >= 500
        ? '服务暂时不可用，请稍后重试。'
        : '链接无效、已过期或密码不符合安全要求。';
      return;
    }
    setupToken = null;
    idempotencyKey = null;
    form.hidden = true;
    status.className = 'status success';
    status.textContent = '密码设置成功。现在可以返回登录页面。';
  } catch (_) {
    newPassword.value = '';
    confirmPassword.value = '';
    status.className = 'status error';
    status.textContent = '网络连接失败，请检查连接后重试。';
  } finally {
    submitButton.disabled = false;
    submitButton.textContent = '设置密码';
  }
});

readAndClearToken();
