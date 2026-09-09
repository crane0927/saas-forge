import { readFile } from 'node:fs/promises';
import { stripVTControlCharacters } from 'node:util';

const log = await readFile(process.argv[2], 'utf8');
// TAP 的标题、error、actual、expected 和堆栈文本都可能含凭据；只输出固定字段及已知源码位置。
let diagnosticIndent;
let field;
for (const line of stripVTControlCharacters(log).split('\n')) {
  if (/^\{"(?:platform|console|api|remote)\./.test(line)) {
    try {
      const observations = JSON.parse(line);
      for (const [hostname, value] of Object.entries(observations)) {
        if (!/^(?:platform|console|api|remote)\.saasforge\.(?:test|example\.com)$/.test(hostname))
          continue;
        const allowed = Number.isInteger(value) && value >= 100 && value <= 599;
        const networkCodes = [
          'ERR_CONNECTION_CLOSED',
          'ERR_CONNECTION_RESET',
          'ERR_CONNECTION_REFUSED',
          'ERR_TIMED_OUT',
          'ERR_NAME_NOT_RESOLVED',
          'ERR_CERT_AUTHORITY_INVALID',
          'ERR_CERT_COMMON_NAME_INVALID',
          'ERR_CERT_DATE_INVALID',
          'BROWSER_NAVIGATION_TIMEOUT',
          'BROWSER_NAVIGATION_UNAVAILABLE',
          'NO_RESPONSE',
        ];
        console.info(
          `TLS: host=${hostname} result=${allowed || networkCodes.includes(value) ? value : 'UNAVAILABLE'}`,
        );
      }
    } catch {
      // 非结构化日志不进入公开摘要。
    }
  }
  const indent = /^\s*/.exec(line)[0].length;
  if (diagnosticIndent === undefined && line.trim() === '---') {
    diagnosticIndent = indent;
    continue;
  }
  if (diagnosticIndent !== undefined) {
    if (indent === diagnosticIndent && line.trim() === '...') {
      diagnosticIndent = undefined;
      field = undefined;
      continue;
    }
    if (indent === diagnosticIndent) field = /^(\w+):/.exec(line.trim())?.[1];
    if (field === 'error' && indent > diagnosticIndent) {
      const detail =
        /^initial-password-change status=[1-5]\d{2} cookieStored=(?:true|false) cookieObserved=(?:true|false) requestMatches=(?:true|false) problem=(?:PASSWORD_CHANGE_SESSION_INVALID|REFRESH_SESSION_INVALID|BROWSER_REQUEST_REJECTED|VALIDATION_FAILED|OTHER)$/.exec(
          line.trim(),
        );
      if (detail) console.info(`DIAG: ${detail[0]}`);
      const cookie =
        /^auth-cookie operation=(?:login|refresh|password-changes) status=[1-5]\d{2} action=(?:none|set|clear|mixed) attributes=(?:true|false)$/.exec(
          line.trim(),
        );
      if (cookie) console.info(`DIAG: ${cookie[0]}`);
      const inventory =
        /^auth-cookie-inventory platform=\d{1,2} tenant=\d{1,2} other=\d{1,2} partitioned=\d{1,2}$/.exec(
          line.trim(),
        );
      if (inventory) console.info(`DIAG: ${inventory[0]}`);
      const remote =
        /^brand-remote inherited=(?:true|false) providers=\d{1,2} images=\d{1,2} faviconUnchanged=(?:true|false) context=\d{1,2} imageRequests=\d{1,2} fetchRequests=\d{1,2} otherRequests=\d{1,2}$/.exec(
          line.trim(),
        );
      if (remote) console.info(`DIAG: ${remote[0]}`);
    }
    if (
      field === 'code' &&
      indent === diagnosticIndent &&
      /: ['"]?ERR_ASSERTION['"]?$/.test(line)
    ) {
      console.info('CODE: ERR_ASSERTION');
    }
    // 仅接受结构化 location 和 stack；actual/error 多行内容不能冒充诊断字段。
    if (
      (field === 'location' && indent === diagnosticIndent) ||
      (field === 'stack' && indent > diagnosticIndent)
    ) {
      const source =
        /\b(consoles\/integration-test\/(?:console-authentication\.test|console-client-acceptance|console-problem-acceptance|brand-remote-acceptance|brand-concurrency-acceptance|static-remote-acceptance|session-tabs\.test|console-default-realm\.test)\.mjs:\d+:\d+)\b/.exec(
          line,
        );
      if (source) console.info(`AT: ${source[1]}`);
    }
    continue;
  }
  // Vitest 和 Node spec reporter 不使用 TAP 字段；仅保留固定错误码及已知测试文件位置。
  // Maven 原始错误可含请求正文；只保留仓库模块白名单与数字统计。
  const mavenModule =
    /^\[INFO\] (saas-forge(?:-(?:openapi-contracts|protobuf-contracts|http-route-catalog|quality-gates|contracts|sdk|java|starters|bom|spring-boot-starter|sdk-(?:core|auth|tenant|permission|feature|quota|audit)))?|gateway|iam-service|tenant-access-service|entitlement-service|audit-service) \.{2,} FAILURE\b/.exec(
      line,
    );
  if (mavenModule) console.info(`MAVEN: failed module=${mavenModule[1]}`);
  const mavenTests =
    /^\[ERROR\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\b/.exec(line);
  if (mavenTests)
    console.info(
      `MAVEN: tests=${mavenTests[1]} failures=${mavenTests[2]} errors=${mavenTests[3]} skipped=${mavenTests[4]}`,
    );
  if (/^\s*[✖×] /.test(line)) console.info('FAIL: compatibility test');
  const compatibilityCode =
    /\b(MojoFailureException|MojoExecutionException|OutOfMemoryError|AssertionError|TimeoutError|ERR_ASSERTION|ERR_MODULE_NOT_FOUND|ERR_PNPM_VERIFY_DEPS_BEFORE_RUN|ERR_PNPM_NO_SCRIPT|ERR_PNPM_RECURSIVE_RUN_FIRST_FAIL|ERR_PNPM_UNSUPPORTED_ENGINE|ECONNREFUSED|EADDRINUSE|ENOSPC|EACCES|ETIMEDOUT)\b/.exec(
      line,
    );
  if (compatibilityCode) console.info(`CODE: ${compatibilityCode[1]}`);
  const compatibilitySource =
    /\b(consoles\/integration-test\/(?:session-tabs|console-default-realm)\.test\.mjs:\d+:\d+)\b/.exec(
      line,
    );
  if (compatibilitySource) console.info(`AT: ${compatibilitySource[1]}`);
  const consumer = /\bbrowser-test\/design-system-consumers\.browser\.test\.tsx:\d+:\d+\b/.exec(
    line,
  );
  if (consumer) console.info(`AT: consoles/${consumer[0]}`);
  const showcase = /\bbrowser-test\/showcase\.browser\.test\.tsx:\d+:\d+\b/.exec(line);
  if (showcase) console.info(`AT: consoles/shared/design-system/${showcase[0]}`);
  const failure = /^\s*not ok (\d+)\b/.exec(line);
  if (failure) console.info(`FAIL: test ${failure[1]}`);
  if (/^# (?:tests|pass|fail|cancelled|skipped|todo|duration_ms) \d+(?:\.\d+)?$/.test(line)) {
    console.info(line);
  }
}
