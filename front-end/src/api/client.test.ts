import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getReadiness, getSystem, getProjects } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('API boundary', () => {
  it('aborts a stalled authenticated request with a controlled timeout message', async () => {
    const deadline = new AbortController();
    vi.spyOn(AbortSignal, 'timeout').mockReturnValue(deadline.signal);
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_path, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener(
              'abort',
              () => reject(new DOMException('Aborted', 'AbortError')),
              { once: true },
            );
          }),
      ),
    );
    const request = getProjects();
    const assertion = expect(request).rejects.toThrow('The request timed out. Please try again.');
    deadline.abort();
    await assertion;
  });
  it('loads later project pages instead of hiding projects beyond the default page', async () => {
    const fetch = vi.fn((path: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            items: [{ id: path.includes('page=1') ? 'project-21' : 'project-1' }],
            total: 21,
            page: path.includes('page=1') ? 1 : 0,
            size: 20,
          }),
        ),
      ),
    );
    vi.stubGlobal('fetch', fetch);
    expect((await getProjects()).items.map((project) => project.id)).toEqual([
      'project-1',
      'project-21',
    ]);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it('accepts dependency failure as a valid health response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'DOWN' }), { status: 503 })),
    );
    await expect(getReadiness()).resolves.toEqual({ status: 'DOWN' });
  });
  it('rejects malformed metadata rather than displaying guessed values', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ name: 'test' }))),
    );
    await expect(getSystem()).rejects.toThrow('unexpected response');
  });
  it('handles authorization failures without exposing server internals', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('private stack trace', { status: 401 })),
    );
    await expect(getSystem()).rejects.toMatchObject({
      status: 401,
      message: 'Your session has expired. Please sign in again.',
    });
  });
  it('turns a network failure into an actionable message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('private connection details')));
    await expect(getSystem()).rejects.toBeInstanceOf(ApiError);
    await expect(getSystem()).rejects.toThrow('Unable to reach the service');
  });
});
