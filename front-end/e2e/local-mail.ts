import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const outbox = path.resolve(process.env.E2E_MAIL_OUTBOX ?? '../.local/mail');

export async function waitForVerificationLink(email: string): Promise<string> {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const files = await readdir(outbox).catch(() => []);
    for (const file of files) {
      if (!file.endsWith('.txt')) continue;
      const message = await readFile(path.join(outbox, file), 'utf8');
      if (!message.startsWith(`To: ${email}\n`)) continue;
      const link = message.match(/https?:\/\/\S+\/verify-email#token=[A-Za-z0-9_-]{43}/)?.[0];
      if (link) return link;
    }
    await delay(200);
  }
  throw new Error(`No local verification email arrived for ${email}.`);
}
