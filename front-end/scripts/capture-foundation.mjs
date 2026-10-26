import { mkdir } from 'node:fs/promises';
import { fileURLToPath, URL } from 'node:url';
import process from 'node:process';
import { log } from 'node:console';
import { chromium } from 'playwright';

const output = fileURLToPath(new URL('../../docs/screenshots/', import.meta.url));
await mkdir(output, { recursive: true });
const browser = await chromium.launch();
try {
  for (const [name, viewport] of [
    ['desktop', { width: 1440, height: 1080 }],
    ['mobile', { width: 390, height: 844 }],
  ]) {
    const page = await browser.newPage({ viewport });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173');
    await page.getByRole('status').filter({ hasText: 'Services connected' }).waitFor();
    await page.screenshot({ path: `${output}/foundation-${name}.png`, fullPage: true });
    await page.getByRole('button', { name: 'Open setup guide' }).click();
    await page.getByRole('heading', { name: 'Local setup' }).waitFor();
    await page.screenshot({ path: `${output}/setup-${name}.png`, fullPage: true });
    if (errors.length) throw new Error(`Browser errors: ${errors.join('; ')}`);
    log(`${name}: captured workspace and setup; no browser exceptions`);
    await page.close();
  }
} finally {
  await browser.close();
}
