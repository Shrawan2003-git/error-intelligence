import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.unstubAllGlobals();
});

function deferredResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

function sessionResponse() {
  return new Response(
    JSON.stringify({
      accessToken: 'new-session-token',
      expiresIn: 600,
      account: { id: 'user', activeOrganizationId: 'organization', organizations: [] },
    }),
  );
}

async function setup(delayedPath: string) {
  vi.resetModules();
  const api = await import('./client');
  const pending = deferredResponse();
  const started = deferredResponse();
  const fetch = vi.fn((path: string) => {
    if (path.endsWith('/csrf'))
      return Promise.resolve(
        new Response(JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })),
      );
    if (path === delayedPath) {
      started.resolve(new Response());
      return pending.promise;
    }
    if (path.endsWith('/logout')) return Promise.resolve(new Response(null, { status: 204 }));
    return Promise.resolve(sessionResponse());
  });
  vi.stubGlobal('fetch', fetch);
  return { api, pending, started: started.promise, fetch };
}

it.each(['login', 'organization'] as const)(
  'does not restore account state when delayed %s completes after logout',
  async (operation) => {
    const { api, pending, started } = await setup(`/api/v1/auth/${operation}`);
    const listener = vi.fn();
    const unsubscribe = api.onSessionChange(listener);
    try {
      const request =
        operation === 'login'
          ? api.signIn('user@example.test', 'password')
          : api.switchOrganization('organization');
      const assertion = expect(request).rejects.toThrow('The session changed.');
      await started;
      await api.signOut();
      pending.resolve(sessionResponse());
      await assertion;
      expect(listener.mock.calls).toEqual([[undefined]]);
    } finally {
      unsubscribe();
    }
  },
);

it('registration does not create a browser session', async () => {
  const { api, pending, started } = await setup('/api/v1/auth/register');
  const listener = vi.fn();
  const unsubscribe = api.onSessionChange(listener);
  try {
    const registration = api.signUp('user@example.test', 'a-strong-password-123', 'User', 'Team');
    await started;
    pending.resolve(new Response(null, { status: 202 }));
    await registration;
    expect(listener).not.toHaveBeenCalled();
  } finally {
    unsubscribe();
  }
});

it('clears local state immediately and does not clear a newer login when logout finishes', async () => {
  const { api, pending, started } = await setup('/api/v1/auth/logout');
  const listener = vi.fn();
  const unsubscribe = api.onSessionChange(listener);
  try {
    const logout = api.signOut();
    expect(listener.mock.calls).toEqual([[undefined]]);
    await started;
    const account = await api.signIn('user@example.test', 'password');
    pending.resolve(new Response(null, { status: 204 }));
    await logout;
    expect(listener.mock.calls).toEqual([[undefined], [account]]);
  } finally {
    unsubscribe();
  }
});

it('does not refresh or clear a newer login after an older request returns unauthorized', async () => {
  const { api, pending, started, fetch } = await setup('/api/v1/projects');
  const request = api.getProjects();
  const assertion = expect(request).rejects.toMatchObject({ status: 401 });
  await started;
  const listener = vi.fn();
  const unsubscribe = api.onSessionChange(listener);
  try {
    const account = await api.signIn('user@example.test', 'password');
    pending.resolve(new Response(null, { status: 401 }));
    await assertion;
    expect(listener.mock.calls).toEqual([[account]]);
    expect(fetch.mock.calls.some(([path]) => path.endsWith('/refresh'))).toBe(false);
  } finally {
    unsubscribe();
  }
});
