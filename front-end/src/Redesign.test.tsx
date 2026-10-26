import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import Application from './Application';

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/');
});
function services({
  authenticated = false,
  role = 'OWNER',
  status = 'UP',
  cacheRequired = false,
} = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      const json = (value: unknown, status = 200) =>
        new Response(JSON.stringify(value), { status });
      if (path.endsWith('/csrf')) return json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' });
      if (path.endsWith('/refresh'))
        return authenticated
          ? json({
              accessToken: 'memory-only',
              expiresIn: 600,
              account: {
                id: 'user',
                displayName: 'Test User',
                email: 'test@example.test',
                activeOrganizationId: 'org',
                organizations: [{ id: 'org', name: 'Test workspace', role }],
              },
            })
          : new Response(null, { status: 401 });
      if (path === '/api/v1/projects') return json({ items: [], total: 0, size: 20, page: 0 });
      if (path.includes('/members')) return json([]);
      if (path === '/api/v1/system')
        return json({
          name: 'Error Intelligence',
          version: '0.1.0-SNAPSHOT',
          phase: 'PHASE_8',
          cacheRequired,
        });
      if (path === '/actuator/health/readiness')
        return json({ status }, status === 'UP' ? 200 : 503);
      return new Response(null, { status: 503 });
    }),
  );
}
function show(path: string) {
  window.history.replaceState({}, '', path);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <Application />
    </QueryClientProvider>,
  );
}
it('uses separate sign-in and account-creation routes without workspace chrome', async () => {
  services();
  show('/login');
  await screen.findByRole('heading', { name: 'Sign in to your workspace' });
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('link', { name: 'Create an account' }));
  expect(window.location.pathname).toBe('/register');
  expect(screen.getByRole('heading', { name: 'Create your account' })).toBeInTheDocument();
  expect(screen.getByLabelText('Full name')).toBeInTheDocument();
  expect(screen.getByLabelText('Workspace name')).toBeInTheDocument();
});
it('redirects unauthenticated deep links to sign in', async () => {
  services();
  show('/app/incidents/private');
  await screen.findByRole('heading', { name: 'Sign in to your workspace' });
  await waitFor(() => expect(window.location.pathname).toBe('/login'));
  expect(screen.queryByText('Incident inbox')).not.toBeInTheDocument();
});
it('shows real readiness and backend version on the service page', async () => {
  services({ authenticated: true });
  show('/app/status');
  await screen.findByText('Services connected');
  expect(screen.getByText('Version 0.1.0-SNAPSHOT')).toBeInTheDocument();
  expect(screen.getByText(/Native development uses local rate limits/)).toBeInTheDocument();
  expect(screen.getByText('Not reported')).toBeInTheDocument();
});
it('distinguishes an available API from failed dependency readiness', async () => {
  services({ authenticated: true, status: 'DOWN' });
  show('/app/status');
  await screen.findByText('Services need attention');
  expect(screen.getByText('Connected', { exact: true })).toBeInTheDocument();
  expect(screen.getByText('DOWN')).toBeInTheDocument();
});
it('blocks direct administrative routes for viewers', async () => {
  services({ authenticated: true, role: 'VIEWER' });
  show('/app/team');
  await screen.findByRole('heading', { name: 'Access restricted' });
  expect(screen.queryByRole('button', { name: 'Add member' })).not.toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: 'Manage' })).not.toBeInTheDocument();
});
it('shows an empty workspace instead of sample dashboard figures', async () => {
  services({ authenticated: true });
  show('/app');
  await screen.findByRole('heading', { name: 'Your first project starts here' });
  expect(screen.queryByText('1,284')).not.toBeInTheDocument();
});
