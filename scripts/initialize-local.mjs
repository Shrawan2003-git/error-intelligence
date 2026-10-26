import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const destination = fileURLToPath(new URL('../.env', import.meta.url));
const databasePassword = randomBytes(32).toString('hex');
const redisPassword = randomBytes(32).toString('hex');
const signingKey = randomBytes(32).toString('base64');
const content = `# Generated local-only credentials. Never commit this file.
POSTGRES_DB=error_intelligence
POSTGRES_USER=error_intelligence
POSTGRES_PASSWORD=${databasePassword}
POSTGRES_PORT=15432
REDIS_PASSWORD=${redisPassword}
REDIS_PORT=6379
SERVER_PORT=8080
FRONTEND_PORT=5173
DB_URL=jdbc:postgresql://localhost:15432/error_intelligence
DB_USERNAME=error_intelligence
DB_PASSWORD=${databasePassword}
REDIS_HOST=localhost
SPRING_PROFILES_ACTIVE=native
AUTH_SIGNING_KEY=${signingKey}
`;

try {
  await writeFile(destination, content, { flag: 'wx', mode: 0o600 });
  console.log('Created local .env with random credentials. Values are intentionally not printed.');
} catch (error) {
  if (error.code === 'EEXIST') {
    console.log('An existing .env was preserved.');
  } else {
    throw error;
  }
}
