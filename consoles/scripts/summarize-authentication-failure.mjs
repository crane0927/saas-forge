import { readFile } from 'node:fs/promises';

const log = await readFile(process.argv[2], 'utf8');
// TAP 的标题、error、actual、expected 和堆栈文本都可能含凭据；只输出固定字段及已知源码位置。
for (const line of log.split('\n')) {
  const failure = /^\s*not ok (\d+)\b/.exec(line);
  if (failure) console.info(`FAIL: test ${failure[1]}`);
  const source =
    /\b(consoles\/integration-test\/(?:console-authentication\.test|console-client-acceptance|console-problem-acceptance)\.mjs:\d+:\d+)\b/.exec(
      line,
    );
  if (source) console.info(`AT: ${source[1]}`);
  if (/^\s*code: ['"]?ERR_ASSERTION['"]?\s*$/.test(line)) console.info('CODE: ERR_ASSERTION');
  if (/^# (?:tests|pass|fail|cancelled|skipped|todo|duration_ms) \d+(?:\.\d+)?$/.test(line)) {
    console.info(line);
  }
}
