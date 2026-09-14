import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { chromium } from 'playwright';

// 仅用于实际后台计时。普通 Playwright Context 的 focus 仿真会令隐藏标签报告 visible。
export async function launchUnemulatedChrome() {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-console-native-chrome-'));
  const executable =
    process.env.SF_CHROME_EXECUTABLE ??
    (process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : 'google-chrome');
  const child = spawn(
    executable,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${directory}`,
      '--no-first-run',
      '--no-default-browser-check',
      'about:blank',
    ],
    { stdio: 'ignore' },
  );
  const exited = new Promise((resolve) => {
    child.once('exit', resolve);
    child.once('error', resolve);
  });
  let browser;
  async function close() {
    await browser?.close();
    if (child.exitCode === null) child.kill('SIGTERM');
    await exited;
    await rm(directory, { recursive: true, force: true });
  }
  try {
    let port;
    for (let attempt = 0; attempt < 100; attempt++) {
      try {
        port = (await readFile(path.join(directory, 'DevToolsActivePort'), 'utf8')).split('\n')[0];
        break;
      } catch {
        await setTimeout(100);
      }
    }
    if (!port) throw new Error('Temporary Chrome startup unavailable');
    browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { noDefaults: true });
    return { context: browser.contexts()[0], close };
  } catch (error) {
    await close();
    throw error;
  }
}
