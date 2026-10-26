/* global URL, console */
import { mkdir } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from '@playwright/test';

const root = new URL('../../', import.meta.url);
const video = fileURLToPath(new URL('docs/demo/error-intelligence-walkthrough.webm', root));
const work = fileURLToPath(new URL('.local/demo-video/', root));
await mkdir(work, { recursive: true });

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  await page.goto(pathToFileURL(video).href);
  const metadata = await page.locator('video').evaluate(async (element) => {
    if (element.readyState < 1) {
      await new Promise((resolve, reject) => {
        element.addEventListener('loadedmetadata', resolve, { once: true });
        element.addEventListener('error', reject, { once: true });
      });
    }
    return { duration: element.duration, width: element.videoWidth, height: element.videoHeight };
  });
  if (metadata.duration < 420 || metadata.duration > 430) {
    throw new Error(`Unexpected video duration: ${metadata.duration}`);
  }
  if (metadata.width !== 1280 || metadata.height !== 720) {
    throw new Error(`Unexpected video dimensions: ${metadata.width}x${metadata.height}`);
  }

  for (const second of [2, 190, 418]) {
    await page.locator('video').evaluate(async (element, target) => {
      element.currentTime = target;
      await new Promise((resolve, reject) => {
        element.addEventListener('seeked', resolve, { once: true });
        element.addEventListener('error', reject, { once: true });
      });
      await element.play();
      await new Promise((resolve) => element.requestVideoFrameCallback(resolve));
      element.pause();
    }, second);
    await page.locator('video').screenshot({ path: `${work}qa-${second}.png` });
  }
  console.log(JSON.stringify(metadata));
} finally {
  await browser.close();
}
