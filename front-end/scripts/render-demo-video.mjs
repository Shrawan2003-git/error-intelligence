/* global URL, atob, btoa, AudioContext, Image, document, MediaStream, MediaRecorder, setInterval, clearInterval, setTimeout, Blob, window, console */
import { readFile, mkdir, open } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { Buffer } from 'node:buffer';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const root = new URL('../../', import.meta.url);
const work = new URL('.local/demo-video/', root);
const publicFolder = new URL('docs/demo/', root);
const smoke = process.argv.includes('--smoke');
const timeline = JSON.parse(await readFile(new URL('timeline.json', work), 'utf8'));
const slides = await Promise.all(
  timeline.map(
    async (scene) =>
      `data:image/png;base64,${(await readFile(new URL(`slides/${scene.slide}`, work))).toString('base64')}`,
  ),
);
const audio = (await readFile(new URL('narration.wav', work))).toString('base64');
const destination = fileURLToPath(smoke ? new URL('smoke.webm', work) : new URL('raw.webm', work));
await mkdir(fileURLToPath(smoke ? work : publicFolder), { recursive: true });

const browser = await chromium.launch({
  headless: true,
  args: ['--autoplay-policy=no-user-gesture-required'],
});
const output = await open(destination, 'w');
try {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.exposeFunction('writeDemoChunk', async (encoded) => {
    await output.write(Buffer.from(encoded, 'base64'));
  });
  await page.goto('about:blank');
  await page
    .evaluate(
      async ({ slides, timeline, audio, smoke }) => {
        const decoded = atob(audio);
        const bytes = new Uint8Array(decoded.length);
        for (let index = 0; index < decoded.length; index++)
          bytes[index] = decoded.charCodeAt(index);
        const sound = new AudioContext();
        await sound.resume();
        const buffer = await sound.decodeAudioData(bytes.buffer);
        const destination = sound.createMediaStreamDestination();
        const source = sound.createBufferSource();
        source.buffer = buffer;
        source.connect(destination);

        const images = await Promise.all(
          slides.map(
            (url) =>
              new Promise((resolve, reject) => {
                const picture = new Image();
                picture.onload = () => resolve(picture);
                picture.onerror = reject;
                picture.src = url;
              }),
          ),
        );
        const canvas = document.createElement('canvas');
        canvas.width = 1280;
        canvas.height = 720;
        const painter = canvas.getContext('2d', { alpha: false });
        const video = canvas.captureStream(0);
        const videoTrack = video.getVideoTracks()[0];
        const stream = new MediaStream([
          ...video.getVideoTracks(),
          ...destination.stream.getAudioTracks(),
        ]);
        const mime = [
          'video/webm;codecs=vp9,opus',
          'video/webm;codecs=vp8,opus',
          'video/webm',
        ].find((candidate) => MediaRecorder.isTypeSupported(candidate));
        if (!mime) throw new Error('This browser cannot record WebM with audio.');
        const recorder = new MediaRecorder(stream, {
          mimeType: mime,
          videoBitsPerSecond: 2_400_000,
          audioBitsPerSecond: 128_000,
        });
        const chunks = [];
        recorder.ondataavailable = (event) => {
          if (event.data.size) chunks.push(event.data);
        };
        const finished = new Promise((resolve, reject) => {
          recorder.onerror = (event) => reject(new Error(String(event.error)));
          recorder.onstop = resolve;
        });
        const duration = smoke ? 4 : timeline.at(-1).end;
        const starts = sound.currentTime + 0.15;
        let lastIndex = 0;
        let frameCount = 0;
        function draw() {
          const elapsed = Math.max(0, sound.currentTime - starts);
          while (lastIndex < timeline.length - 1 && elapsed >= timeline[lastIndex].end) lastIndex++;
          const scene = timeline[lastIndex];
          painter.drawImage(images[lastIndex], 0, 0, 1280, 720);
          if (!smoke && lastIndex < timeline.length - 1 && scene.end - elapsed < 0.75) {
            painter.globalAlpha = Math.max(0, Math.min(1, (elapsed - scene.end + 0.75) / 0.75));
            painter.drawImage(images[lastIndex + 1], 0, 0, 1280, 720);
            painter.globalAlpha = 1;
          }
          videoTrack.requestFrame();
          frameCount++;
        }
        recorder.start(1000);
        const frameTicker = setInterval(draw, 100);
        draw();
        source.start(starts, 0, duration);
        await new Promise((resolve) => {
          source.onended = resolve;
        });
        await new Promise((resolve) => setTimeout(resolve, 250));
        clearInterval(frameTicker);
        recorder.stop();
        await finished;
        for (const track of stream.getTracks()) track.stop();
        await sound.close();
        const blob = new Blob(chunks, { type: mime });
        for (let offset = 0; offset < blob.size; offset += 256 * 1024) {
          const bytes = new Uint8Array(await blob.slice(offset, offset + 256 * 1024).arrayBuffer());
          let binary = '';
          for (let index = 0; index < bytes.length; index += 8192) {
            binary += String.fromCharCode(...bytes.subarray(index, index + 8192));
          }
          await window.writeDemoChunk(btoa(binary));
        }
        return { frames: frameCount, bytes: blob.size, chunks: chunks.length };
      },
      { slides, timeline, audio, smoke },
    )
    .then((result) => console.log(`Recorded ${JSON.stringify(result)}`));
  await output.sync();
  const size = (await readFile(destination)).byteLength;
  if (!smoke) {
    const encoder = fileURLToPath(new URL('.tools/playwright/ffmpeg-1013/ffmpeg-win64.exe', root));
    const final = fileURLToPath(new URL('error-intelligence-walkthrough.webm', publicFolder));
    await new Promise((resolve, reject) => {
      const remux = spawn(
        encoder,
        ['-y', '-loglevel', 'error', '-i', destination, '-c', 'copy', final],
        {
          stdio: 'inherit',
        },
      );
      remux.on('error', reject);
      remux.on('exit', (code) =>
        code === 0 ? resolve() : reject(new Error(`Remux failed: ${code}`)),
      );
    });
    console.log(`Saved narrated walkthrough: ${(await readFile(final)).byteLength} bytes`);
  } else {
    console.log(`Saved smoke check: ${size} bytes`);
  }
} finally {
  await output.close();
  await browser.close();
}
