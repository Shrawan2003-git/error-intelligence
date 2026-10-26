import { expect, test } from '@playwright/test';

test('registers an owner and creates a tenant-scoped project', async ({ page }) => {
  const projects: Array<{
    id: string;
    name: string;
    slug: string;
    createdAt: string;
    archivedAt: null;
  }> = [];
  const ingestionKeys: Array<{
    id: string;
    label: string;
    prefix: string;
    scope: string;
    createdAt: string;
    revokedAt: string | null;
  }> = [];
  let incidentStatus = 'OPEN';
  const comments: Array<{
    id: string;
    authorId: string;
    authorName: string;
    body: string;
    createdAt: string;
  }> = [];
  const resolutions: Array<{
    id: string;
    summary: string;
    rootCause: string;
    verifiedBy: string;
    verifierName: string;
    verifiedAt: string;
  }> = [];
  let diagnosticRequested = false;
  const incident = {
    id: 'incident-1',
    title: 'com.example.PaymentException',
    status: incidentStatus,
    assigneeId: null,
    occurrenceCount: 2,
    firstSeen: '2026-10-09T00:00:00Z',
    lastSeen: '2026-10-09T00:01:00Z',
    environment: 'production',
    release: 'release-1',
  };
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'browser-csrf', headerName: 'X-XSRF-TOKEN' } }),
  );
  await page.route('**/api/v1/auth/refresh', (route) => route.fulfill({ status: 401 }));
  await page.route('**/api/v1/audit?limit=50', (route) =>
    route.fulfill({
      json: {
        limit: 50,
        items: [
          {
            id: 'audit-1',
            actorId: 'owner-1',
            action: 'PROJECT_CREATED',
            targetId: 'project-1',
            createdAt: '2026-10-09T00:00:00Z',
          },
        ],
      },
    }),
  );
  await page.route('**/api/v1/auth/register', async (route) => {
    expect(route.request().headers()['x-xsrf-token']).toBe('browser-csrf');
    expect(route.request().headers()['cookie']).toBeUndefined();
    await route.fulfill({ status: 202 });
  });
  await page.route('**/api/v1/auth/verification/confirm', (route) =>
    route.fulfill({ status: 204 }),
  );
  await page.route('**/api/v1/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        accessToken: 'browser-only-access-token',
        expiresIn: 600,
        account: {
          id: 'owner-1',
          email: 'owner@example.test',
          displayName: 'Sam Dev',
          organizations: [{ id: 'org-1', name: 'Platform', role: 'OWNER' }],
          activeOrganizationId: 'org-1',
        },
      },
    });
  });
  await page.route('**/api/v1/projects', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON() as { name: string; slug: string };
      projects.push({
        ...payload,
        id: 'project-1',
        createdAt: '2026-10-09T00:00:00Z',
        archivedAt: null,
      });
      await route.fulfill({
        status: 201,
        json: projects[0],
      });
    } else {
      await route.fulfill({
        json: { items: projects, total: projects.length, page: 0, size: 20 },
      });
    }
  });
  await page.route('**/api/v1/projects/project-1/analytics/overview**', async (route) => {
    await route.fulfill({
      json: {
        days: 30,
        eventCount: 12,
        incidentCount: 1,
        openCount: 1,
        acknowledgedCount: 0,
        resolvedCount: 0,
        resolutionRate: 0,
        dailyEvents: [{ date: '2026-10-09', count: 12 }],
        topIncidents: [
          { title: 'com.example.PaymentException', occurrenceCount: 12, status: 'OPEN' },
        ],
      },
    });
  });
  await page.route('**/api/v1/organizations/org-1/members', async (route) => {
    if (route.request().method() === 'POST') await route.fulfill({ status: 201 });
    else await route.fulfill({ json: [] });
  });
  await page.route('**/api/v1/projects/project-1/keys**', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON() as { label: string };
      ingestionKeys.push({
        id: 'key-1',
        label: payload.label,
        prefix: 'ei_testkey',
        scope: 'INGEST',
        createdAt: '2026-10-09T00:00:00Z',
        revokedAt: null,
      });
      await route.fulfill({
        status: 201,
        json: { ...ingestionKeys[0], secret: 'ei_one-time-secret' },
      });
    } else if (route.request().method() === 'DELETE') {
      ingestionKeys[0].revokedAt = '2026-10-09T00:01:00Z';
      await route.fulfill({ status: 204 });
    } else {
      await route.fulfill({ json: ingestionKeys });
    }
  });
  await page.route('**/api/v1/projects/project-1/incidents**', async (route) => {
    await route.fulfill({
      json: { items: [{ ...incident, status: incidentStatus }], total: 1, page: 0, size: 50 },
    });
  });
  await page.route('**/api/v1/incidents/incident-1**', async (route) => {
    const method = route.request().method();
    const url = route.request().url();
    if (url.endsWith('/ai/analyze') && method === 'POST') {
      diagnosticRequested = true;
      await route.fulfill({
        status: 202,
        json: { analysisId: 'analysis-1', status: 'QUEUED', createdAt: '2026-10-09T00:04:00Z' },
      });
    } else if (url.endsWith('/similar') && method === 'POST') {
      await route.fulfill({
        json: {
          embeddingModel: 'text-embedding-3-small',
          items: [
            {
              sourceId: 'resolution-similar',
              incidentId: 'incident-2',
              sourceType: 'RESOLUTION',
              title: 'GatewayTimeoutException',
              summary: 'Raised the upstream timeout.',
              rootCause: 'Gateway timeout was too low.',
              similarity: 0.91,
            },
          ],
        },
      });
    } else if (url.endsWith('/ai/analyses/analysis-1')) {
      expect(diagnosticRequested).toBe(true);
      await route.fulfill({
        json: {
          id: 'analysis-1',
          incidentId: 'incident-1',
          status: 'SUCCEEDED',
          model: 'gpt-4.1-mini',
          promptVersion: 'diagnostic-v1',
          result: {
            category: 'NETWORK',
            confidence: 0.82,
            summary: 'The payment request may be failing at the gateway.',
            evidence: [{ field: 'message', excerpt: 'Payment failed' }],
            hypotheses: [
              {
                cause: 'Gateway timeout',
                rationale: 'The failure occurred during the outbound call.',
                confidence: 0.82,
              },
            ],
            diagnosticSteps: [
              {
                order: 1,
                action: 'Check gateway latency.',
                expectedSignal: 'Elevated request duration.',
                riskLevel: 'LOW',
              },
            ],
            relatedResolutionIds: [],
            limitations: ['Review with an operator before taking action.'],
          },
          failureCode: null,
          attemptCount: 1,
          inputTokens: 300,
          outputTokens: 120,
          createdAt: '2026-10-09T00:04:00Z',
          updatedAt: '2026-10-09T00:04:01Z',
          finishedAt: '2026-10-09T00:04:01Z',
        },
      });
    } else if (method === 'PATCH') {
      incidentStatus = (route.request().postDataJSON() as { status: string }).status;
      await route.fulfill({ json: { ...incident, status: incidentStatus } });
    } else if (route.request().url().endsWith('/comments')) {
      const payload = route.request().postDataJSON() as { body: string };
      comments.push({
        id: 'comment-1',
        authorId: 'owner-1',
        authorName: 'Sam Dev',
        body: payload.body,
        createdAt: '2026-10-09T00:02:00Z',
      });
      await route.fulfill({
        status: 201,
        json: { id: 'comment-1', createdAt: '2026-10-09T00:02:00Z' },
      });
    } else if (route.request().url().endsWith('/resolve')) {
      const payload = route.request().postDataJSON() as { summary: string; rootCause: string };
      incidentStatus = 'RESOLVED';
      resolutions.push({
        id: 'resolution-1',
        ...payload,
        verifiedBy: 'owner-1',
        verifierName: 'Sam Dev',
        verifiedAt: '2026-10-09T00:03:00Z',
      });
      await route.fulfill({
        status: 201,
        json: { id: 'resolution-1', createdAt: '2026-10-09T00:03:00Z' },
      });
    } else {
      await route.fulfill({
        json: {
          incident: { ...incident, status: incidentStatus },
          recentEvents: [
            {
              id: 'event-1',
              occurredAt: '2026-10-09T00:01:00Z',
              environment: 'production',
              release: 'release-1',
              exceptionType: 'com.example.PaymentException',
              message: 'Payment failed',
              stackTrace: 'at com.example.Payments.run(Payments.java:42)',
              requestMetadata: '{}',
            },
          ],
          comments,
          resolutions,
        },
      });
    }
  });

  await page.goto('/register');
  await page.getByLabel('Full name').fill('Sam Dev');
  await page.getByLabel('Workspace name').fill('Platform');
  await page.getByLabel('Email address').fill('owner@example.test');
  await page.getByLabel('Password', { exact: true }).fill('correct-horse-battery-staple');
  await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  await page.goto('/verify-email#token=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG');
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByText('Your email is verified. You can now sign in.')).toBeVisible();
  await page.getByRole('link', { name: 'Continue to sign in' }).click();
  await page.getByLabel('Email address').fill('owner@example.test');
  await page.getByLabel('Password', { exact: true }).fill('correct-horse-battery-staple');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your first project starts here' })).toBeVisible();
  await page.getByRole('button', { name: 'Create project', exact: true }).click();
  await page.getByLabel('Project name').fill('Payments API');
  await page.getByLabel('URL slug').fill('payments-api');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Create project', exact: true })
    .click();
  await expect(page).toHaveURL(/\/app\/projects\/project-1$/);
  await expect(page.getByRole('heading', { name: 'Payments API', exact: true })).toBeVisible();
  await expect(page.getByText('Events', { exact: true }).first()).toBeVisible();
  await page.getByRole('link', { name: 'Ingestion keys', exact: true }).click();
  await page.getByRole('button', { name: 'Create key', exact: true }).click();
  await page.getByLabel('Key label').fill('production api');
  await page.getByRole('dialog').getByRole('button', { name: 'Create key', exact: true }).click();
  await expect(page.getByText('ei_one-time-secret')).toBeVisible();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByText('ei_one-time-secret')).toHaveCount(0);
  await page.getByRole('button', { name: 'Revoke production api' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Revoke key', exact: true }).click();
  await expect(page.getByText('Revoked', { exact: true })).toBeVisible();
  await page.evaluate(() => {
    history.pushState({}, '', '/app/incidents');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: 'Incident inbox' })).toBeVisible();
  await page.getByRole('link', { name: 'com.example.PaymentException', exact: true }).click();
  await page.getByRole('link', { name: 'Related incidents', exact: true }).click();
  await page.getByRole('button', { name: 'Find similar incidents' }).click();
  await expect(page.getByText('GatewayTimeoutException')).toBeVisible();
  await expect(page.getByText(/Gateway timeout was too low/)).toBeVisible();
  await page.getByRole('link', { name: 'AI diagnostics', exact: true }).click();
  await page.getByRole('button', { name: 'Analyze incident' }).click();
  await expect(page.getByText('The payment request may be failing at the gateway.')).toBeVisible();
  await expect(page.getByText('Gateway timeout', { exact: true })).toBeVisible();
  await expect(page.getByText('Check gateway latency.')).toBeVisible();
  await page.getByRole('link', { name: 'Evidence & activity' }).click();
  await page.getByLabel('Status', { exact: true }).selectOption('ACKNOWLEDGED');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.locator('.badge').filter({ hasText: 'Acknowledged' })).toBeVisible();
  await page.getByLabel('Comment', { exact: true }).fill('Investigating the production failure');
  await page.getByRole('button', { name: 'Post comment' }).click();
  await expect(
    page.getByText('Investigating the production failure', { exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Verify & resolve' }).click();
  await page.getByLabel('Resolution summary').fill('Database pool leak fixed');
  await page.getByLabel('Root cause').fill('Connections were not released');
  await page.getByRole('button', { name: 'Verify resolution' }).click();
  await expect(page.getByText('Connections were not released', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});
