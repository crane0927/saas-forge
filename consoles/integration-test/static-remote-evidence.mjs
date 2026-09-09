/** 只投影允许公开的观测字段；未知网络值不能原样进入持久证据。 */
export function staticRemoteEvidence({ passed, records, errors, rendering, tenantOrigin }) {
  const allowedOrigins = [
    'https://console.saasforge.test',
    'https://console.saasforge.example.com',
  ];
  return {
    schemaVersion: 1,
    status: passed ? 'passed' : 'failed',
    requests: records.map((record) => ({
      path: /^\/static-acceptance\/v[12]\/(remote\.js|styles\.css|image\.svg)$/.test(
        record.path ?? '',
      )
        ? record.path
        : 'unexpected',
      method: ['GET', 'HEAD'].includes(record.method) ? record.method : 'unexpected',
      status:
        Number.isInteger(record.status) && record.status >= 100 && record.status <= 599
          ? record.status
          : null,
      credentialHeaders: Array.isArray(record.credentials)
        ? ['cookie', 'authorization', 'x-sf-csrf'].filter((name) =>
            record.credentials.includes(name),
          )
        : null,
      allowOrigin:
        record.allowOrigin === null
          ? 'absent'
          : allowedOrigins.includes(tenantOrigin) && record.allowOrigin === tenantOrigin
            ? tenantOrigin
            : 'unexpected-or-unobserved',
      allowCredentials: record.allowCredentials === null ? 'absent' : 'present-or-unobserved',
      contentType: [
        'text/javascript; charset=utf-8',
        'text/css; charset=utf-8',
        'image/svg+xml',
      ].includes(record.contentType)
        ? record.contentType
        : 'unexpected-or-unobserved',
    })),
    consoleErrors: errors.map((error) =>
      ['pageerror', 'console-error'].includes(error) ? error : 'unknown-error',
    ),
    rendering: rendering.map((item) => ({
      version: ['v1', 'v2'].includes(item.version) ? item.version : 'unexpected',
      moduleExecuted: item.moduleExecuted === true,
      borderTopWidth: ['7px', '11px'].includes(item.borderTopWidth)
        ? item.borderTopWidth
        : 'unexpected',
      imageDimensions: item.imageDimensions.map((value) =>
        Number.isInteger(value) && value >= 0 && value <= 10000 ? value : null,
      ),
    })),
  };
}
