export interface Organization {
  id: string;
  name: string;
  role: 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER';
}
export interface Account {
  id: string;
  email: string;
  displayName: string;
  organizations: Organization[];
  activeOrganizationId: string;
}
export interface SessionResponse {
  accessToken: string;
  expiresIn: number;
  account: Account;
}
export interface Project {
  id: string;
  name: string;
  slug: string;
  createdAt: string;
  archivedAt: string | null;
}
export interface ProjectPage {
  items: Project[];
  total: number;
  page: number;
  size: number;
}
export interface Member {
  userId: string;
  displayName: string;
  email: string;
  role: Organization['role'];
}
export interface IngestionKey {
  id: string;
  label: string;
  prefix: string;
  scope: string;
  createdAt: string;
  revokedAt: string | null;
}
export interface CreatedIngestionKey {
  metadata: IngestionKey;
  secret: string;
}

export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
export interface IncidentSummary {
  id: string;
  title: string;
  status: IncidentStatus;
  assigneeId: string | null;
  occurrenceCount: number;
  firstSeen: string;
  lastSeen: string;
  environment: string | null;
  release: string | null;
}
export interface IncidentPage {
  items: IncidentSummary[];
  total: number;
  page: number;
  size: number;
}
export interface IncidentEvent {
  id: string;
  occurredAt: string;
  environment: string;
  release: string | null;
  exceptionType: string;
  message: string;
  stackTrace: string;
  requestMetadata: string;
}
export interface IncidentComment {
  id: string;
  authorId: string;
  authorName: string;
  body: string;
  createdAt: string;
}
export interface IncidentResolution {
  id: string;
  summary: string;
  rootCause: string;
  verifiedBy: string;
  verifierName: string;
  verifiedAt: string;
}
export interface IncidentDetail {
  incident: IncidentSummary;
  recentEvents: IncidentEvent[];
  comments: IncidentComment[];
  resolutions: IncidentResolution[];
}

export type DiagnosticStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'RETRYING' | 'FALLBACK';
export interface DiagnosticResult {
  category: 'DATABASE' | 'DI' | 'AUTH' | 'NETWORK' | 'VALIDATION' | 'APPLICATION' | 'UNKNOWN';
  confidence: number;
  summary: string;
  evidence: Array<{ field: string; excerpt: string }>;
  hypotheses: Array<{ cause: string; rationale: string; confidence: number }>;
  diagnosticSteps: Array<{
    order: number;
    action: string;
    expectedSignal: string;
    riskLevel: 'LOW' | 'MEDIUM';
  }>;
  relatedResolutionIds: string[];
  limitations: string[];
}
export interface DiagnosticAnalysis {
  id: string;
  incidentId: string;
  status: DiagnosticStatus;
  model: string;
  promptVersion: string;
  result: DiagnosticResult | null;
  failureCode: string | null;
  attemptCount: number;
  inputTokens: number | null;
  outputTokens: number | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
}
export interface SimilarityMatch {
  sourceId: string;
  incidentId: string;
  sourceType: 'INCIDENT' | 'RESOLUTION';
  title: string;
  summary: string | null;
  rootCause: string | null;
  similarity: number;
}
export interface SimilaritySearchResult {
  items: SimilarityMatch[];
  embeddingModel: string;
}
export interface AnalyticsOverview {
  days: number;
  eventCount: number;
  incidentCount: number;
  openCount: number;
  acknowledgedCount: number;
  resolvedCount: number;
  resolutionRate: number;
  dailyEvents: Array<{ date: string; count: number }>;
  topIncidents: Array<{ title: string; occurrenceCount: number; status: IncidentStatus }>;
}

export interface AuditEntry {
  id: string;
  actorId: string | null;
  action: string;
  targetId: string | null;
  createdAt: string;
}
