import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, Search, Sparkles } from 'lucide-react';
import {
  addIncidentComment,
  findSimilarIncidents,
  getDiagnostic,
  getIncident,
  getIncidents,
  requestDiagnostic,
  resolveIncident,
  updateIncident,
  type IncidentDetail,
  type IncidentStatus,
  type IncidentSummary,
} from '../api/client';
import { incidentPath } from '../navigation';
import { Link } from '../Link';
import { Badge, Empty, Failure, Heading, Loading, Modal, Notice, Panel, StatusBadge } from '../ui';
import { date, initials, useTask } from '../ui-helpers';
import { useWorkspace } from '../workspace';
import { ProjectPicker } from './InsightPages';
import { useProjectSelection } from '../hooks/useProjectSelection';

export function IncidentsPage() {
  const { account, loading, error } = useWorkspace(),
    { selected, select } = useProjectSelection();
  const [status, setStatus] = useState<IncidentStatus | ''>('');
  const [search, setSearch] = useState(''),
    [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const result = useQuery({
    queryKey: [
      'organization',
      account.activeOrganizationId,
      'incidents',
      selected?.id,
      status,
      query,
      page,
    ],
    queryFn: () => getIncidents(selected!.id, { status: status || undefined, q: query, page }),
    enabled: !!selected,
  });
  return (
    <>
      <Heading
        title="Incident inbox"
        description="Find the signal. Follow the evidence. Keep investigations moving."
      />
      {loading ? (
        <Loading />
      ) : error ? null : !selected ? (
        <Empty
          title="No projects yet"
          action={
            <Link className="button" href="/app/projects">
              View projects
            </Link>
          }
        >
          Connect a project to start receiving errors.
        </Empty>
      ) : (
        <>
          <form
            className="filters"
            onSubmit={(e) => {
              e.preventDefault();
              setQuery(search);
              setPage(0);
            }}
          >
            <ProjectPicker
              id={selected.id}
              onChange={(id) => {
                select(id);
                setPage(0);
              }}
            />
            <label className="search-field">
              Search incidents
              <input
                placeholder="Search by title"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </label>
            <label>
              Status
              <select
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value as IncidentStatus | '');
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                <option value="OPEN">Open</option>
                <option value="ACKNOWLEDGED">Acknowledged</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </label>
            <button className="button" type="submit">
              <Search size={16} />
              Search
            </button>
          </form>
          {result.isPending ? (
            <Loading label="Loading incidents…" />
          ) : result.error ? (
            <Failure error={result.error} retry={() => void result.refetch()} />
          ) : !result.data.items.length ? (
            <Empty
              title={query || status ? 'No matching incidents' : 'No incidents yet'}
              action={
                <Link
                  className="button"
                  href={`/app/setup?project=${encodeURIComponent(selected.id)}`}
                >
                  Connection guide
                </Link>
              }
            >
              {query || status
                ? 'Try another search or status filter.'
                : 'Once your application sends an error, it will appear here ready to investigate.'}
            </Empty>
          ) : (
            <>
              <Panel title="Incidents" action={<Badge>{result.data.total} total</Badge>}>
                <IncidentTable items={result.data.items} />
              </Panel>
              <div className="row spread">
                <span className="muted small">
                  Page {page + 1} · {result.data.total} incidents
                </span>
                <div className="row">
                  <button className="button" disabled={!page} onClick={() => setPage(page - 1)}>
                    Previous
                  </button>
                  <button
                    className="button"
                    disabled={(page + 1) * result.data.size >= result.data.total}
                    onClick={() => setPage(page + 1)}
                  >
                    Next
                  </button>
                </div>
              </div>
            </>
          )}
        </>
      )}
    </>
  );
}
function IncidentTable({ items }: { items: IncidentSummary[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Incident</th>
            <th>Status</th>
            <th className="number">Events</th>
            <th>Last seen</th>
          </tr>
        </thead>
        <tbody>
          {items.map((incident) => (
            <tr key={incident.id}>
              <td>
                <Link className="incident-title" href={incidentPath(incident.id)}>
                  {incident.title}
                </Link>
                <span className="secondary">
                  {incident.environment || 'No environment'}
                  {incident.release ? ` · ${incident.release}` : ''}
                </span>
              </td>
              <td>
                <StatusBadge status={incident.status} />
              </td>
              <td className="number">{incident.occurrenceCount}</td>
              <td>{date(incident.lastSeen)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function IncidentPage({ incidentId, tab }: { incidentId: string; tab: string }) {
  const { account, members, canWrite } = useWorkspace();
  const client = useQueryClient();
  const queryKey = ['organization', account.activeOrganizationId, 'incident', incidentId];
  const result = useQuery({ queryKey, queryFn: () => getIncident(incidentId) });
  const [resolve, setResolve] = useState(false);
  const task = useTask();
  async function refresh() {
    await client.invalidateQueries({ queryKey });
    await client.invalidateQueries({
      queryKey: ['organization', account.activeOrganizationId, 'analytics'],
    });
    await client.invalidateQueries({
      queryKey: ['organization', account.activeOrganizationId, 'incidents'],
    });
  }
  if (result.isPending) return <Loading label="Loading incident…" />;
  if (result.error)
    return (
      <>
        <Link className="back-link" href="/app/incidents">
          ← All incidents
        </Link>
        <Failure error={result.error} retry={() => void result.refetch()} />
      </>
    );
  const detail = result.data,
    incident = detail.incident;
  function submitResolution(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void task.run(async () => {
      await resolveIncident(
        incidentId,
        String(data.get('summary')).trim(),
        String(data.get('rootCause')).trim(),
      );
      await refresh();
      setResolve(false);
    });
  }
  return (
    <>
      <Link className="back-link" href="/app/incidents">
        ← All incidents
      </Link>
      <Heading
        title={
          tab === 'diagnostics'
            ? 'AI diagnostics'
            : tab === 'related'
              ? 'Related incidents'
              : incident.title
        }
        description={tab === 'evidence' ? `Incident ${incident.id}` : incident.title}
        action={
          canWrite &&
          tab === 'evidence' && (
            <button className="button primary" onClick={() => setResolve(true)}>
              <Check size={16} />
              Verify & resolve
            </button>
          )
        }
      />
      <div className="row">
        <StatusBadge status={incident.status} />
        <span className="muted small">{incident.environment || 'No environment'}</span>
        {incident.release && <span className="muted small">Release {incident.release}</span>}
      </div>
      <div className="detail-meta">
        <div>
          <small>Occurrences</small>
          <span>{incident.occurrenceCount} occurrences</span>
        </div>
        <div>
          <small>First seen</small>
          <span>{date(incident.firstSeen)}</span>
        </div>
        <div>
          <small>Last seen</small>
          <span>{date(incident.lastSeen)}</span>
        </div>
        <div>
          <small>Assigned to</small>
          <span>
            {members.find((m) => m.userId === incident.assigneeId)?.displayName ||
              (incident.assigneeId ? 'Workspace member' : 'Unassigned')}
          </span>
        </div>
      </div>
      <nav className="page-tabs" aria-label="Incident pages">
        <Link className={tab === 'evidence' ? 'selected' : ''} href={incidentPath(incidentId)}>
          Evidence & activity
        </Link>
        <Link
          className={tab === 'diagnostics' ? 'selected' : ''}
          href={`${incidentPath(incidentId)}/diagnostics`}
        >
          AI diagnostics
        </Link>
        <Link
          className={tab === 'related' ? 'selected' : ''}
          href={`${incidentPath(incidentId)}/related`}
        >
          Related incidents
        </Link>
      </nav>
      {!canWrite && (
        <Notice>
          Viewer access · You can browse this incident. Editing is available to developers and
          administrators.
        </Notice>
      )}
      {tab === 'diagnostics' ? (
        <Diagnostics incidentId={incidentId} />
      ) : tab === 'related' ? (
        <Related incidentId={incidentId} />
      ) : (
        <div className="investigation-grid">
          <div className="stack">
            <Evidence detail={detail} />
            <Discussion detail={detail} refresh={refresh} />
          </div>
          <div className="stack">
            <Investigation
              key={`${incident.status}-${incident.assigneeId}`}
              detail={detail}
              refresh={refresh}
            />
            <Panel title="Verified resolutions">
              <div className="panel-pad stack">
                {detail.resolutions.length ? (
                  detail.resolutions.map((resolution) => (
                    <article className="resolution" key={resolution.id}>
                      <Badge>Human verified</Badge>
                      <h3>{resolution.summary}</h3>
                      <p className="small preserve-lines">{resolution.rootCause}</p>
                      <small className="muted">
                        {resolution.verifierName} · {date(resolution.verifiedAt)}
                      </small>
                    </article>
                  ))
                ) : (
                  <p className="muted small">
                    Capture the root cause and verified fix when your investigation is complete.
                  </p>
                )}
                {canWrite && (
                  <button className="button" onClick={() => setResolve(true)}>
                    Add resolution
                  </button>
                )}
              </div>
            </Panel>
          </div>
        </div>
      )}
      {resolve && (
        <Modal title="Verify & resolve" onClose={() => setResolve(false)}>
          <form className="form-stack" onSubmit={submitResolution}>
            {task.error ? <Failure error={task.error} /> : null}
            <label>
              Resolution summary
              <input
                name="summary"
                maxLength={500}
                placeholder="Describe the verified fix"
                required
              />
            </label>
            <label>
              Root cause
              <textarea
                name="rootCause"
                maxLength={4000}
                placeholder="What caused the error, and how was the fix verified?"
                required
              />
            </label>
            <button className="button primary" disabled={task.pending}>
              {task.pending ? 'Saving…' : 'Verify resolution'}
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}
function Evidence({ detail }: { detail: IncidentDetail }) {
  const [index, setIndex] = useState(0),
    event = detail.recentEvents[index] || detail.recentEvents[0];
  return (
    <Panel title="Event evidence">
      <div className="panel-pad stack">
        {!event ? (
          <p className="muted">
            Raw event evidence is no longer available. Incident history is retained.
          </p>
        ) : (
          <>
            {detail.recentEvents.length > 1 && (
              <label>
                Event
                <select value={index} onChange={(e) => setIndex(Number(e.target.value))}>
                  {detail.recentEvents.map((item, i) => (
                    <option key={item.id} value={i}>
                      {date(item.occurredAt)}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <h3>{event.exceptionType}</h3>
            <p className="small preserve-lines">{event.message}</p>
            <pre className="code">{event.stackTrace || 'No stack trace supplied.'}</pre>
            <small className="muted">
              {date(event.occurredAt)} · {event.environment}
            </small>
            {event.requestMetadata && (
              <details>
                <summary>Request metadata</summary>
                <pre className="code">{event.requestMetadata}</pre>
              </details>
            )}
          </>
        )}
      </div>
    </Panel>
  );
}
function Investigation({
  detail,
  refresh,
}: {
  detail: IncidentDetail;
  refresh: () => Promise<void>;
}) {
  const { members, canWrite } = useWorkspace(),
    task = useTask();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    void task.run(async () => {
      await updateIncident(
        detail.incident.id,
        String(values.get('status')) as IncidentStatus,
        String(values.get('assignee')) || null,
      );
      await refresh();
    });
  }
  return (
    <Panel title="Investigation">
      <form className="panel-pad form-stack" onSubmit={submit}>
        {task.error ? <Failure error={task.error} /> : null}
        <label>
          Status
          <select
            name="status"
            aria-label="Status"
            defaultValue={detail.incident.status}
            disabled={!canWrite}
          >
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            {detail.incident.status === 'RESOLVED' && <option value="RESOLVED">Resolved</option>}
          </select>
        </label>
        <label>
          Assignee
          <select
            name="assignee"
            defaultValue={detail.incident.assigneeId || ''}
            disabled={!canWrite}
          >
            <option value="">Unassigned</option>
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.displayName}
              </option>
            ))}
          </select>
        </label>
        {canWrite && (
          <button className="button" disabled={task.pending}>
            {task.pending ? 'Saving…' : 'Save changes'}
          </button>
        )}
      </form>
    </Panel>
  );
}
function Discussion({ detail, refresh }: { detail: IncidentDetail; refresh: () => Promise<void> }) {
  const { canWrite } = useWorkspace(),
    task = useTask();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget,
      body = String(new FormData(form).get('comment')).trim();
    void task.run(async () => {
      await addIncidentComment(detail.incident.id, body);
      form.reset();
      await refresh();
    });
  }
  return (
    <Panel title="Discussion">
      <div className="panel-pad stack">
        {detail.comments.map((comment) => (
          <article className="comment" key={comment.id}>
            <div className="row">
              <span className="avatar">{initials(comment.authorName)}</span>
              <div>
                <h3>{comment.authorName}</h3>
                <small className="muted">{date(comment.createdAt)}</small>
              </div>
            </div>
            <p className="small preserve-lines">{comment.body}</p>
          </article>
        ))}
        {!detail.comments.length && (
          <p className="muted small">
            No comments yet. Share evidence or your next investigation step.
          </p>
        )}
        {canWrite && (
          <form className="form-stack" onSubmit={submit}>
            {task.error ? <Failure error={task.error} /> : null}
            <label>
              Comment
              <textarea
                name="comment"
                placeholder="Share evidence or your next step…"
                maxLength={4000}
                required
              />
            </label>
            <div>
              <button className="button" disabled={task.pending}>
                {task.pending ? 'Posting…' : 'Post comment'}
              </button>
            </div>
          </form>
        )}
      </div>
    </Panel>
  );
}
function Diagnostics({ incidentId }: { incidentId: string }) {
  const { account, canWrite } = useWorkspace(),
    client = useQueryClient(),
    task = useTask();
  const latestKey = ['organization', account.activeOrganizationId, 'latest-analysis', incidentId];
  const [analysisId, setAnalysisId] = useState(() => client.getQueryData<string>(latestKey) || '');
  const result = useQuery({
    queryKey: ['organization', account.activeOrganizationId, 'analysis', incidentId, analysisId],
    queryFn: () => getDiagnostic(incidentId, analysisId),
    enabled: !!analysisId,
    refetchInterval: (query) =>
      query.state.data && ['QUEUED', 'RUNNING', 'RETRYING'].includes(query.state.data.status)
        ? 1500
        : false,
  });
  const analysis = result.data,
    output = analysis?.result;
  const busy =
    task.pending ||
    (!!analysisId && result.isPending) ||
    (!!analysis && ['QUEUED', 'RUNNING', 'RETRYING'].includes(analysis.status));
  return (
    <>
      <Notice>
        AI provides hypotheses, not verified fixes. Analysis requires configured provider
        credentials, budgets, and enabled workers.
      </Notice>
      <div className="row spread">
        <p className="muted small">Start an analysis when you’re ready to investigate.</p>
        {canWrite && (
          <button
            className="button primary"
            disabled={busy}
            onClick={() =>
              void task.run(async () => {
                const accepted = await requestDiagnostic(incidentId);
                client.setQueryData(latestKey, accepted.analysisId);
                setAnalysisId(accepted.analysisId);
              })
            }
          >
            <Sparkles size={16} />
            {busy ? 'Analysis in progress…' : 'Analyze incident'}
          </button>
        )}
      </div>
      {task.error ? <Failure error={task.error} /> : null}
      {result.error ? <Failure error={result.error} retry={() => void result.refetch()} /> : null}
      {!analysisId ? (
        <Empty title="No analysis yet">
          Request an analysis to see evidence, possible causes, and investigation steps.
        </Empty>
      ) : result.isPending ? (
        <Loading label="Checking analysis status…" />
      ) : analysis ? (
        <>
          <div className="row" role="status">
            <Badge>{analysis.status}</Badge>
            <span className="muted small">
              Updated {date(analysis.updatedAt)} · Attempt {analysis.attemptCount}
            </span>
          </div>
          {analysis.status === 'QUEUED' && (
            <Notice>
              Waiting for an enabled AI worker. A disabled local worker will leave this request
              queued.
            </Notice>
          )}
          {analysis.failureCode && (
            <Notice error>
              Analysis could not complete: {analysis.failureCode}. Continue with the event evidence
              and manual investigation.
            </Notice>
          )}
          {output && (
            <>
              <Panel title="Analysis summary">
                <div className="panel-pad stack">
                  <div className="row">
                    <Badge>{output.category}</Badge>
                    <Badge>Model confidence · {Math.round(output.confidence * 100)}%</Badge>
                  </div>
                  <h2>{output.summary}</h2>
                  <small className="muted">AI hypothesis · Requires human verification</small>
                </div>
              </Panel>
              <div className="equal-grid">
                <Panel title="Supporting evidence">
                  <div className="panel-pad stack">
                    {output.evidence.map((evidence, i) => (
                      <div key={i}>
                        <h3>{evidence.field}</h3>
                        <pre className="code">{evidence.excerpt}</pre>
                      </div>
                    ))}
                  </div>
                </Panel>
                <Panel title="Possible causes">
                  <div className="panel-pad stack">
                    {output.hypotheses.map((hypothesis, i) => (
                      <div key={i}>
                        <h3>{hypothesis.cause}</h3>
                        <p className="muted small">{hypothesis.rationale}</p>
                        <small className="muted">
                          Model confidence: {Math.round(hypothesis.confidence * 100)}%
                        </small>
                      </div>
                    ))}
                  </div>
                </Panel>
              </div>
              <Panel title="Suggested investigation">
                <ol className="panel-pad investigation-steps">
                  {output.diagnosticSteps.map((step) => (
                    <li key={step.order}>
                      <div>
                        <h3>{step.action}</h3>
                        <p className="muted small">{step.expectedSignal}</p>
                      </div>
                      <Badge tone={step.riskLevel === 'MEDIUM' ? 'ack' : ''}>
                        {step.riskLevel.toLowerCase()} risk
                      </Badge>
                    </li>
                  ))}
                </ol>
              </Panel>
              {output.limitations.map((limitation, i) => (
                <p className="footnote" key={i}>
                  Limitation: {limitation}
                </p>
              ))}
            </>
          )}
        </>
      ) : null}
    </>
  );
}
function Related({ incidentId }: { incidentId: string }) {
  const { account, canWrite } = useWorkspace();
  const result = useQuery({
    queryKey: ['organization', account.activeOrganizationId, 'similar', incidentId],
    queryFn: () => findSimilarIncidents(incidentId),
    enabled: false,
    retry: false,
  });
  return (
    <>
      <Notice>
        Matches stay within this project. Similarity measures relevance, not proof that the same fix
        applies.
      </Notice>
      <div className="row spread">
        <p className="muted small">Search requires enabled semantic retrieval.</p>
        {canWrite && (
          <button
            className="button primary"
            disabled={result.isFetching}
            onClick={() => void result.refetch()}
          >
            <Search size={16} />
            {result.isFetching ? 'Finding matches…' : 'Find similar incidents'}
          </button>
        )}
      </div>
      {result.isFetching ? (
        <Loading label="Finding related incidents…" />
      ) : result.error ? (
        <Failure error={result.error} />
      ) : !result.data ? (
        <Empty title="Find a useful starting point">
          Search prior incidents and verified resolutions in this project.
        </Empty>
      ) : !result.data.items.length ? (
        <Empty title="No related incidents found">
          More incident history and verified resolutions will improve future matches.
        </Empty>
      ) : (
        result.data.items.map((match) => (
          <Panel className="panel-pad stack" key={`${match.sourceType}-${match.sourceId}`}>
            <div className="row spread">
              <Badge>
                {match.sourceType === 'RESOLUTION' ? 'Verified resolution' : 'Related incident'}
              </Badge>
              <small className="muted">{Math.round(match.similarity * 100)}% similarity</small>
            </div>
            <h2>{match.title}</h2>
            {match.summary && <p>{match.summary}</p>}
            {match.rootCause && <p className="muted small">Root cause: {match.rootCause}</p>}
            <Link className="text-link" href={incidentPath(match.incidentId)}>
              Open incident
              <ArrowRight size={16} />
            </Link>
          </Panel>
        ))
      )}
    </>
  );
}
