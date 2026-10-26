import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import Application from './Application';

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/');
});

it('creates an account with CSRF protection and keeps the access token out of browser storage', async () => {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      requests.push({ path, init });
      if (path === '/api/v1/auth/csrf') {
        return Promise.resolve(
          new Response(JSON.stringify({ token: 'csrf-value', headerName: 'X-XSRF-TOKEN' }), {
            status: 200,
          }),
        );
      }
      if (path === '/api/v1/auth/refresh') {
        return Promise.resolve(new Response('', { status: 401 }));
      }
      if (path === '/api/v1/auth/register') {
        return Promise.resolve(new Response(null, { status: 202 }));
      }
      if (path === '/api/v1/projects') {
        return Promise.resolve(
          new Response(JSON.stringify({ items: [], total: 0, page: 0, size: 20 }), { status: 200 }),
        );
      }
      return Promise.resolve(new Response('', { status: 404 }));
    }),
  );
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <Application />
    </QueryClientProvider>,
  );

  await screen.findByRole('heading', { name: 'Sign in to your workspace' });
  fireEvent.click(screen.getByRole('link', { name: 'Create an account' }));
  await screen.findByRole('heading', { name: 'Create your account' });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Dev User' } });
  fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: 'Platform' } });
  fireEvent.change(screen.getByLabelText('Email address'), {
    target: { value: 'dev@example.test' },
  });
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'correct-horse-battery-staple' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Create workspace' }));

  expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument();
  expect(requests.some((request) => request.path === '/api/v1/projects')).toBe(false);
  const registration = requests.find((request) => request.path === '/api/v1/auth/register');
  expect(registration?.init?.headers).toMatchObject({ 'X-XSRF-TOKEN': 'csrf-value' });
  expect(registration?.init?.credentials).toBe('same-origin');
  expect(localStorage.getItem('accessToken')).toBeNull();
});

function authenticatedWorkspace(failLogout = false) {
  let active = 'org-a';
  const account = () => ({
    id: 'scope-user',
    email: 'scope@example.test',
    displayName: 'Scope User',
    activeOrganizationId: active,
    organizations: [
      { id: 'org-a', name: 'Alpha', role: 'OWNER' },
      { id: 'org-b', name: 'Beta', role: 'OWNER' },
    ],
  });
  const json = (value: unknown) => Promise.resolve(new Response(JSON.stringify(value)));
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/csrf')) return json({ token: 'test', headerName: 'X-XSRF-TOKEN' });
      if (path.endsWith('/refresh'))
        return json({ accessToken: 'test-access', expiresIn: 600, account: account() });
      if (path.endsWith('/auth/organization')) {
        active = 'org-b';
        return json({ accessToken: 'next-access', expiresIn: 600, account: account() });
      }
      if (path.endsWith('/auth/logout'))
        return failLogout
          ? Promise.reject(new TypeError('offline'))
          : Promise.resolve(new Response(null, { status: 204 }));
      if (path === '/api/v1/projects') {
        if (active === 'org-b') return Promise.reject(new TypeError('offline'));
        return json({
          items: [{ id: 'alpha-project', name: 'Alpha secret project', slug: 'alpha' }],
          total: 1,
          page: 0,
          size: 20,
        });
      }
      if (path.includes('/members'))
        return active === 'org-b'
          ? Promise.reject(new TypeError('offline'))
          : json([
              {
                userId: 'member-a',
                displayName: 'Alpha member',
                email: 'member@example.test',
                role: 'DEVELOPER',
              },
            ]);
      if (path.includes('/analytics/'))
        return json({
          eventCount: 0,
          incidentCount: 0,
          openCount: 0,
          acknowledgedCount: 0,
          resolvedCount: 0,
          resolutionRate: 0,
          dailyEvents: [],
          topIncidents: [],
        });
      if (path.includes('/incidents')) return json({ items: [], total: 0, page: 0, size: 50 });
      return json({ items: [], limit: 50 });
    }),
  );
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

it("never retains another organization's data when the next organization cannot load", async () => {
  const client = authenticatedWorkspace();
  render(
    <QueryClientProvider client={client}>
      <Application />
    </QueryClientProvider>,
  );
  await screen.findAllByText('Alpha secret project');
  fireEvent.click(screen.getByRole('link', { name: 'Open account' }));
  await screen.findByRole('heading', { name: 'Account & workspaces' });
  fireEvent.click(await screen.findByRole('button', { name: 'Switch to Beta' }));
  await waitFor(() => expect(screen.queryAllByText('Alpha secret project')).toHaveLength(0));
  expect(screen.queryByText('Alpha member')).not.toBeInTheDocument();
  await screen.findByText('Unable to reach the service. Please try again.');
});

it('clears local account and tenant data even when remote logout fails', async () => {
  const client = authenticatedWorkspace(true);
  render(
    <QueryClientProvider client={client}>
      <Application />
    </QueryClientProvider>,
  );
  await screen.findAllByText('Alpha secret project');
  fireEvent.click(screen.getByRole('link', { name: 'Open account' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
  await screen.findByRole('heading', { name: 'Sign in to your workspace' });
  expect(screen.queryByText('Scope User')).not.toBeInTheDocument();
  expect(screen.queryAllByText('Alpha secret project')).toHaveLength(0);
});
