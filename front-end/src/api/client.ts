import type {
  Organization,
  Account,
  SessionResponse,
  Project,
  ProjectPage,
  Member,
  IngestionKey,
  CreatedIngestionKey,
  IncidentStatus,
  IncidentSummary,
  IncidentPage,
  IncidentDetail,
  DiagnosticStatus,
  DiagnosticAnalysis,
  SimilaritySearchResult,
  AnalyticsOverview,
  AuditEntry,
} from './contracts';
export type * from './contracts';

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly code?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request(path: string, signal?: AbortSignal, acceptedStatuses: number[] = []) {
  try {
    const timeout = AbortSignal.timeout(8_000);
    const response = await fetch(path, {
      headers: { Accept: 'application/json' },
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    });
    if (!response.ok && !acceptedStatuses.includes(response.status)) {
      const message =
        response.status === 401
          ? 'Your session has expired. Please sign in again.'
          : response.status === 403
            ? 'You do not have access to this resource.'
            : 'The service could not complete the request.';
      throw new ApiError(message, response.status);
    }
    return (await response.json()) as unknown;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError('Unable to reach the service. Check the backend and try again.');
  }
}

let accessToken: string | undefined;
let refreshPromise: Promise<SessionResponse> | undefined;
let sessionGeneration = 0;
const sessionListeners = new Set<(account: Account | undefined) => void>();

export function onSessionChange(listener: (account: Account | undefined) => void) {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

function notifySession(account: Account | undefined) {
  sessionListeners.forEach((listener) => listener(account));
}

function sessionLock<T>(operation: () => Promise<T>): Promise<T> {
  return typeof navigator !== 'undefined' && navigator.locks
    ? navigator.locks.request('error-intelligence-session', operation)
    : operation();
}

async function boundedFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const timeout = AbortSignal.timeout(20_000);
  try {
    return await fetch(path, {
      ...options,
      signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout,
    });
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      timeout.aborted
        ? 'The request timed out. Please try again.'
        : 'Unable to reach the service. Please try again.',
    );
  }
}

async function csrfToken(signal?: AbortSignal) {
  const response = await boundedFetch('/api/v1/auth/csrf', { credentials: 'same-origin', signal });
  if (!response.ok) throw new ApiError('Could not prepare a secure request.', response.status);
  return (await response.json()) as { token: string; headerName: string };
}

async function secureRequest<T>(
  path: string,
  options: { method?: string; body?: unknown; authenticated?: boolean; signal?: AbortSignal } = {},
  retried = false,
): Promise<T> {
  const generation = sessionGeneration;
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.authenticated && accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (method !== 'GET' && method !== 'HEAD') {
    const token = await csrfToken(options.signal);
    headers[token.headerName] = token.token;
  }
  const response = await boundedFetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    signal: options.signal,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!response.ok) {
    if (response.status === 401 && generation !== sessionGeneration)
      throw new ApiError('The session changed. Please sign in again.', 401);
    if (response.status === 401 && options.authenticated && !retried) {
      try {
        const session = await refreshSession();
        acceptSession(session, generation);
        return secureRequest<T>(path, options, true);
      } catch {
        if (generation === sessionGeneration) {
          accessToken = undefined;
          notifySession(undefined);
        }
      }
    } else if (response.status === 401) {
      accessToken = undefined;
      notifySession(undefined);
    }
    let message = 'The service could not complete the request.';
    let code: string | undefined;
    try {
      const problem = (await response.json()) as { detail?: string; code?: string };
      if (problem.detail) message = problem.detail;
      code = problem.code;
    } catch {
      // Keep the safe fallback for non-JSON proxy errors.
    }
    throw new ApiError(message, response.status, code);
  }
  if (response.status === 204) return undefined as T;
  const body = await response.text();
  return (body ? JSON.parse(body) : undefined) as T;
}

// Responses may finish after logout or a newer account/organization selection.
function acceptSession(result: SessionResponse, generation: number) {
  if (generation !== sessionGeneration)
    throw new ApiError('The session changed. Please sign in again.', 401);
  accessToken = result.accessToken;
  notifySession(result.account);
  return result.account;
}

export async function signIn(email: string, password: string): Promise<Account> {
  const generation = ++sessionGeneration;
  return acceptSession(
    await sessionLock(() =>
      secureRequest<SessionResponse>('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password },
      }),
    ),
    generation,
  );
}

export async function signUp(
  email: string,
  password: string,
  displayName: string,
  organizationName: string,
): Promise<void> {
  await secureRequest<void>('/api/v1/auth/register', {
    method: 'POST',
    body: { email, password, displayName, organizationName },
  });
}

export async function resendVerification(email: string): Promise<void> {
  await secureRequest<void>('/api/v1/auth/verification/resend', {
    method: 'POST',
    body: { email },
  });
}

export async function confirmVerification(token: string): Promise<void> {
  await secureRequest<void>('/api/v1/auth/verification/confirm', {
    method: 'POST',
    body: { token },
  });
}

export async function requestPasswordReset(email: string): Promise<void> {
  await secureRequest<void>('/api/v1/auth/password/forgot', {
    method: 'POST',
    body: { email },
  });
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await secureRequest<void>('/api/v1/auth/password/reset', {
    method: 'POST',
    body: { token, newPassword },
  });
  sessionGeneration++;
  accessToken = undefined;
  notifySession(undefined);
}

export async function restoreSession(): Promise<Account | undefined> {
  const generation = sessionGeneration;
  try {
    const result = await refreshSession();
    return acceptSession(result, generation);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return undefined;
    throw error;
  }
}

function refreshSession(): Promise<SessionResponse> {
  if (!refreshPromise) {
    const generation = sessionGeneration;
    refreshPromise = sessionLock(async () => {
      if (generation !== sessionGeneration)
        throw new ApiError('The session changed. Please sign in again.', 401);
      const result = await secureRequest<SessionResponse>('/api/v1/auth/refresh', {
        method: 'POST',
      });
      if (generation !== sessionGeneration)
        throw new ApiError('The session changed. Please sign in again.', 401);
      return result;
    }).finally(() => {
      refreshPromise = undefined;
    });
  }
  return refreshPromise;
}

export async function signOut(): Promise<void> {
  sessionGeneration++;
  accessToken = undefined;
  notifySession(undefined);
  await sessionLock(() => secureRequest<void>('/api/v1/auth/logout', { method: 'POST' }));
}

export async function switchOrganization(organizationId: string): Promise<Account> {
  const generation = ++sessionGeneration;
  const result = await secureRequest<SessionResponse>('/api/v1/auth/organization', {
    method: 'POST',
    authenticated: true,
    body: { organizationId },
  });
  return acceptSession(result, generation);
}

export async function createOrganization(name: string): Promise<Organization> {
  return secureRequest('/api/v1/organizations', {
    method: 'POST',
    authenticated: true,
    body: { name },
  });
}

export async function getProjects(signal?: AbortSignal): Promise<ProjectPage> {
  const first = await secureRequest<ProjectPage>('/api/v1/projects', {
    authenticated: true,
    signal,
  });
  const items = [...first.items];
  for (let page = 1; page * first.size < first.total; page++) {
    const next = await secureRequest<ProjectPage>(
      `/api/v1/projects?page=${page}&size=${first.size}`,
      { authenticated: true, signal },
    );
    items.push(...next.items);
  }
  return { ...first, items };
}

export async function createProject(name: string, slug: string): Promise<Project> {
  return secureRequest('/api/v1/projects', {
    method: 'POST',
    authenticated: true,
    body: { name, slug },
  });
}

export async function archiveProject(id: string): Promise<void> {
  return secureRequest(`/api/v1/projects/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    authenticated: true,
  });
}

async function pagedList<T>(path: string, signal?: AbortSignal): Promise<T[]> {
  const result: T[] = [];
  for (let offset = 0; ; offset += 100) {
    const items = await secureRequest<T[]>(offset ? `${path}?offset=${offset}` : path, {
      authenticated: true,
      signal,
    });
    result.push(...items);
    if (items.length < 100) return result;
  }
}

export async function getMembers(organizationId: string, signal?: AbortSignal): Promise<Member[]> {
  return pagedList(`/api/v1/organizations/${encodeURIComponent(organizationId)}/members`, signal);
}

export async function addMember(
  organizationId: string,
  email: string,
  role: Member['role'],
): Promise<void> {
  return secureRequest(`/api/v1/organizations/${encodeURIComponent(organizationId)}/members`, {
    method: 'POST',
    authenticated: true,
    body: { email, role },
  });
}

export async function updateMemberRole(
  organizationId: string,
  userId: string,
  role: Member['role'],
): Promise<void> {
  return secureRequest(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/members/${encodeURIComponent(userId)}`,
    { method: 'PATCH', authenticated: true, body: { role } },
  );
}

export async function removeMember(organizationId: string, userId: string): Promise<void> {
  return secureRequest(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/members/${encodeURIComponent(userId)}`,
    { method: 'DELETE', authenticated: true },
  );
}

export async function listIngestionKeys(projectId: string): Promise<IngestionKey[]> {
  return pagedList(`/api/v1/projects/${encodeURIComponent(projectId)}/keys`);
}

export async function createIngestionKey(
  projectId: string,
  label: string,
): Promise<CreatedIngestionKey> {
  return secureRequest(`/api/v1/projects/${encodeURIComponent(projectId)}/keys`, {
    method: 'POST',
    authenticated: true,
    body: { label },
  });
}

export async function revokeIngestionKey(projectId: string, keyId: string): Promise<void> {
  return secureRequest(
    `/api/v1/projects/${encodeURIComponent(projectId)}/keys/${encodeURIComponent(keyId)}`,
    { method: 'DELETE', authenticated: true },
  );
}

export async function getIncidents(
  projectId: string,
  filters: { status?: IncidentStatus; q?: string; page?: number } = {},
): Promise<IncidentPage> {
  const query = new URLSearchParams({ page: String(filters.page ?? 0), size: '50' });
  if (filters.status) query.set('status', filters.status);
  if (filters.q?.trim()) query.set('q', filters.q.trim());
  return secureRequest(
    `/api/v1/projects/${encodeURIComponent(projectId)}/incidents?${query.toString()}`,
    { authenticated: true },
  );
}

export async function getIncident(incidentId: string): Promise<IncidentDetail> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}`, {
    authenticated: true,
  });
}

export async function requestDiagnostic(incidentId: string): Promise<{
  analysisId: string;
  status: DiagnosticStatus;
  createdAt: string;
}> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}/ai/analyze`, {
    method: 'POST',
    authenticated: true,
  });
}

export async function getDiagnostic(
  incidentId: string,
  analysisId: string,
): Promise<DiagnosticAnalysis> {
  return secureRequest(
    `/api/v1/incidents/${encodeURIComponent(incidentId)}/ai/analyses/${encodeURIComponent(analysisId)}`,
    { authenticated: true },
  );
}

export async function findSimilarIncidents(incidentId: string): Promise<SimilaritySearchResult> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}/similar`, {
    method: 'POST',
    authenticated: true,
  });
}

export async function getAnalyticsOverview(
  projectId: string,
  days = 30,
): Promise<AnalyticsOverview> {
  return secureRequest(
    `/api/v1/projects/${encodeURIComponent(projectId)}/analytics/overview?days=${days}`,
    { authenticated: true },
  );
}

export async function getAuditEvents(limit = 50): Promise<{ items: AuditEntry[]; limit: number }> {
  return secureRequest(`/api/v1/audit?limit=${limit}`, { authenticated: true });
}

export async function updateIncident(
  incidentId: string,
  status: IncidentStatus,
  assigneeId: string | null,
): Promise<IncidentSummary> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}`, {
    method: 'PATCH',
    authenticated: true,
    body: { status, assigneeId },
  });
}

export async function addIncidentComment(incidentId: string, body: string): Promise<void> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}/comments`, {
    method: 'POST',
    authenticated: true,
    body: { body },
  });
}

export async function resolveIncident(
  incidentId: string,
  summary: string,
  rootCause: string,
): Promise<void> {
  return secureRequest(`/api/v1/incidents/${encodeURIComponent(incidentId)}/resolve`, {
    method: 'POST',
    authenticated: true,
    body: { summary, rootCause },
  });
}

export interface IngestionReceipt {
  eventId: string;
  projectId: string;
  duplicate: boolean;
  acceptedAt: string;
}

export async function sendErrorEvent(
  key: string,
  event: {
    eventId: string;
    occurredAt: string;
    environment: string;
    exceptionType: string;
    message: string;
    stackTrace?: string;
  },
): Promise<IngestionReceipt> {
  const response = await fetch('/ingest/v1/events', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-API-Key': key,
    },
    body: JSON.stringify(event),
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) {
    throw new ApiError(
      response.status === 401
        ? 'The ingestion key is invalid or has been revoked.'
        : response.status === 429
          ? 'The ingestion limit was reached. Retry shortly.'
          : 'The event could not be accepted.',
      response.status,
    );
  }
  return (await response.json()) as IngestionReceipt;
}

export interface SystemInfo {
  name: string;
  version: string;
  phase: string;
  cacheRequired: boolean;
}
export interface HealthInfo {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';
}

export async function getSystem(signal?: AbortSignal): Promise<SystemInfo> {
  const data = await request('/api/v1/system', signal);
  if (
    !data ||
    typeof data !== 'object' ||
    !('name' in data) ||
    typeof data.name !== 'string' ||
    !('version' in data) ||
    typeof data.version !== 'string' ||
    !('phase' in data) ||
    typeof data.phase !== 'string' ||
    !('cacheRequired' in data) ||
    typeof data.cacheRequired !== 'boolean'
  ) {
    throw new ApiError('The service returned an unexpected response.');
  }
  return {
    name: data.name,
    version: data.version,
    phase: data.phase,
    cacheRequired: data.cacheRequired,
  };
}

export async function getReadiness(signal?: AbortSignal): Promise<HealthInfo> {
  const data = await request('/actuator/health/readiness', signal, [503]);
  if (
    !data ||
    typeof data !== 'object' ||
    !('status' in data) ||
    !['UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN'].includes(String(data.status))
  ) {
    throw new ApiError('The service returned an unexpected health response.');
  }
  return { status: data.status as HealthInfo['status'] };
}
