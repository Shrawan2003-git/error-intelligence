import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, ArrowRight, Database, RefreshCw, Server, Sparkles } from 'lucide-react';
import {
  getAnalyticsOverview,
  getAuditEvents,
  getReadiness,
  getSystem,
  sendErrorEvent,
  type AnalyticsOverview,
} from '../api/client';
import { projectPath } from '../navigation';
import { Link } from '../Link';
import { Badge, Empty, Failure, Heading, Loading, Panel, StatusBadge } from '../ui';
import { date, useTask } from '../ui-helpers';
import { useProjectSelection } from '../hooks/useProjectSelection';
import { useWorkspace } from '../workspace';
import { CreateProjectButton } from './ProjectPages';

export function ProjectPicker({ id, onChange }: { id: string; onChange: (id: string) => void }) {
  const { projects } = useWorkspace();
  return (
    <label>
      Project
      <select value={id} onChange={(e) => onChange(e.target.value)} aria-label="Project">
        {projects.map((project) => (
          <option key={project.id} value={project.id}>
            {project.name}
          </option>
        ))}
      </select>
    </label>
  );
}
export function Metrics({ data }: { data: AnalyticsOverview }) {
  return (
    <div className="stat-grid">
      {[
        ['Events', data.eventCount.toLocaleString(), 'Retained events in period'],
        ['Open incidents', data.openCount.toLocaleString(), 'First seen in period'],
        ['Acknowledged', data.acknowledgedCount.toLocaleString(), 'Under investigation'],
        [
          'Resolution rate',
          `${Math.round(data.resolutionRate * 100)}%`,
          `${data.resolvedCount} of ${data.incidentCount} incidents`,
        ],
      ].map(([label, value, caption]) => (
        <div className="stat" key={label}>
          <span className="stat-label">{label}</span>
          <strong>{value}</strong>
          <small>{caption}</small>
        </div>
      ))}
    </div>
  );
}
function EventChart({ data }: { data: AnalyticsOverview }) {
  const values = data.dailyEvents,
    max = Math.max(1, ...values.map((x) => x.count)),
    width = 620,
    height = 200,
    barWidth = 550 / Math.max(1, values.length);
  return (
    <Panel title="Daily event volume" action={<small className="muted">UTC calendar days</small>}>
      <div className="event-chart">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          role="img"
          aria-label={`Daily events: ${values.map((x) => `${x.date}: ${x.count}`).join(', ')}`}
        >
          <line x1="45" x2="605" y1="165" y2="165" className="chart-grid" />
          <line x1="45" x2="605" y1="30" y2="30" className="chart-grid" />
          <text x="35" y="34" textAnchor="end">
            {max}
          </text>
          <text x="35" y="169" textAnchor="end">
            0
          </text>
          {values.map((item, index) => (
            <rect
              key={item.date}
              x={50 + index * barWidth}
              y={165 - (item.count / max) * 130}
              width={Math.max(1, barWidth - 4)}
              height={(item.count / max) * 130}
              rx="2"
              className="chart-bar"
            >
              <title>
                {item.date}: {item.count} events
              </title>
            </rect>
          ))}
          <text x="50" y="190">
            {values[0]?.date || 'No events'}
          </text>
          <text x="600" y="190" textAnchor="end">
            {values.at(-1)?.date}
          </text>
          <text x="45" y="15">
            Events
          </text>
        </svg>
      </div>
    </Panel>
  );
}
export function ProjectInsights({ projectId, days = 7 }: { projectId: string; days?: number }) {
  const { account } = useWorkspace();
  const result = useQuery({
    queryKey: ['organization', account.activeOrganizationId, 'analytics', projectId, days],
    queryFn: () => getAnalyticsOverview(projectId, days),
  });
  if (result.isPending) return <Loading label="Loading project analytics…" />;
  if (result.error) return <Failure error={result.error} retry={() => void result.refetch()} />;
  const data = result.data;
  return (
    <>
      <Metrics data={data} />
      {!data.eventCount ? (
        <Empty
          title="Waiting for the first event"
          action={
            <Link className="button" href={`/app/setup?project=${encodeURIComponent(projectId)}`}>
              Connect your application
              <ArrowRight size={16} />
            </Link>
          }
        >
          Charts will appear when this project receives events in the selected period.
        </Empty>
      ) : (
        <div className="insight-grid">
          <EventChart data={data} />
          <Panel title="Incident status">
            <div className="panel-pad stack">
              <p className="muted small">
                {data.incidentCount} incidents first seen in this period
              </p>
              {[
                ['Open', data.openCount],
                ['Acknowledged', data.acknowledgedCount],
                ['Resolved', data.resolvedCount],
              ].map(([label, value]) => (
                <div className="status-bar" key={label}>
                  <span>{label}</span>
                  <meter
                    value={Number(value)}
                    max={Math.max(1, data.incidentCount)}
                    aria-label={`${label} incidents`}
                  />
                  <strong>{value}</strong>
                </div>
              ))}
            </div>
          </Panel>
        </div>
      )}
      <Panel
        title="Most frequent incidents"
        action={
          <Link href={`/app/incidents?project=${encodeURIComponent(projectId)}`}>
            View incidents →
          </Link>
        }
      >
        {data.topIncidents.length ? (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Incident</th>
                  <th>Status</th>
                  <th className="number">Occurrences</th>
                </tr>
              </thead>
              <tbody>
                {data.topIncidents.map((incident, i) => (
                  <tr key={i}>
                    <td>{incident.title}</td>
                    <td>
                      <StatusBadge status={incident.status} />
                    </td>
                    <td className="number">{incident.occurrenceCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="panel-pad muted">No incidents active in this period.</p>
        )}
      </Panel>
    </>
  );
}
export function OverviewPage() {
  const { loading, error, canManage } = useWorkspace(),
    { selected, select } = useProjectSelection();
  return (
    <>
      <Heading
        title="Clarity starts here."
        description="Your project’s errors, investigation, and progress. All in view."
        action={
          <Link className="button primary" href="/app/incidents">
            View incidents
            <ArrowRight size={16} />
          </Link>
        }
      />
      {loading ? (
        <Loading />
      ) : error ? null : !selected ? (
        <Empty
          title="Your first project starts here"
          action={canManage ? <CreateProjectButton /> : undefined}
        >
          {canManage
            ? 'Create a project and connect your application to begin.'
            : 'Ask a workspace administrator to create a project.'}
        </Empty>
      ) : (
        <>
          <div className="filters">
            <ProjectPicker id={selected.id} onChange={select} />
            <Badge>Last 7 days</Badge>
          </div>
          <ProjectInsights projectId={selected.id} />
        </>
      )}
    </>
  );
}
export function AnalyticsPage() {
  const { loading, error } = useWorkspace(),
    { selected, select } = useProjectSelection();
  const [days, setDays] = useState(7);
  return (
    <>
      <Heading
        title="Project analytics"
        description="Less guesswork. A clearer picture of what needs attention."
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
          Connect a project to see its analytics.
        </Empty>
      ) : (
        <>
          <div className="filters">
            <ProjectPicker id={selected.id} onChange={select} />
            <label>
              Period
              <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
                <option value={7}>Last 7 days</option>
                <option value={30}>Last 30 days</option>
                <option value={90}>Last 90 days</option>
              </select>
            </label>
          </div>
          <ProjectInsights projectId={selected.id} days={days} />
          <p className="footnote">
            Status counts cover incidents first seen in the period. Frequent incidents show lifetime
            occurrences for incidents active in the period. Event totals reflect retained raw data.
          </p>
        </>
      )}
    </>
  );
}
export function AuditPage() {
  const { account } = useWorkspace();
  const result = useQuery({
    queryKey: ['organization', account.activeOrganizationId, 'audit'],
    queryFn: () => getAuditEvents(),
  });
  return (
    <>
      <Heading
        title="Audit log"
        description="A record of your workspace’s security and project changes."
        action={<Badge>Owner & Admin</Badge>}
      />
      {result.isPending ? (
        <Loading />
      ) : result.error ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : !result.data.items.length ? (
        <Empty title="No audit events yet">Workspace changes will appear here.</Empty>
      ) : (
        <Panel title="Latest activity">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Action</th>
                  <th>Resource</th>
                  <th>Time</th>
                </tr>
              </thead>
              <tbody>
                {result.data.items.map((entry) => (
                  <tr key={entry.id}>
                    <td>{entry.action.replaceAll('_', ' ')}</td>
                    <td className="mono muted">{entry.targetId || 'Organization event'}</td>
                    <td>{date(entry.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      <p className="footnote">
        Latest 50 events · Available to workspace owners and administrators.
      </p>
    </>
  );
}
export function SetupPage() {
  const { loading, canManage } = useWorkspace(),
    { selected, select } = useProjectSelection();
  const task = useTask();
  const [receipt, setReceipt] = useState('');
  function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget,
      key = String(new FormData(form).get('key')).trim();
    void task.run(async () => {
      const result = await sendErrorEvent(key, {
        eventId: crypto.randomUUID(),
        occurredAt: new Date().toISOString(),
        environment: 'local-demo',
        exceptionType: 'SyntheticCheckoutException',
        message: 'Synthetic checkout failure for connection verification',
        stackTrace: 'at example.CheckoutService.submit(CheckoutService.java:42)',
      });
      form.reset();
      setReceipt(
        `Event accepted${result.duplicate ? ' (duplicate)' : ''}. Open Incidents to investigate it.`,
      );
    });
  }
  return (
    <>
      <Heading
        title="Connect your application"
        description="From your first event to your next breakthrough."
      />
      <div className="setup-steps">
        <span>
          <b>01</b>Create a project
        </span>
        <span>
          <b>02</b>Create a key
        </span>
        <span>
          <b>03</b>Send an event
        </span>
      </div>
      {loading ? (
        <Loading />
      ) : !selected ? (
        <Empty title="Create a project first" action={<CreateProjectButton />}>
          Every error belongs to a project.
        </Empty>
      ) : (
        <>
          <Panel title="01 / Choose your project">
            <div className="panel-pad">
              <ProjectPicker id={selected.id} onChange={select} />
            </div>
          </Panel>
          <Panel title="02 / Create a project key">
            <div className="panel-pad row spread">
              <p className="muted small">
                Use an ingestion key starting with ei_. Keep it in your application’s secret store.
              </p>
              {canManage ? (
                <Link className="button" href={`${projectPath(selected.id)}/keys`}>
                  Ingestion keys
                  <ArrowRight size={16} />
                </Link>
              ) : (
                <span className="muted small">Ask your administrator for a project key.</span>
              )}
            </div>
          </Panel>
          <Panel title="03 / Send a test event">
            <form className="panel-pad form-stack" onSubmit={send}>
              <p className="muted small">
                Send one synthetic error using your project’s ingestion key. The key stays in memory
                and is cleared after sending.
              </p>
              <label>
                Project ingestion key
                <input name="key" type="password" placeholder="ei_…" autoComplete="off" required />
              </label>
              {task.error ? <Failure error={task.error} /> : null}
              {receipt && (
                <p className="notice" role="status">
                  {receipt}
                </p>
              )}
              <div className="row">
                <button className="button primary" disabled={task.pending}>
                  {task.pending ? 'Sending…' : 'Send test event'}
                </button>
                <Link
                  className="button"
                  href={`/app/incidents?project=${encodeURIComponent(selected.id)}`}
                >
                  View incidents
                </Link>
              </div>
            </form>
          </Panel>
          <Panel title="Connect your own sender">
            <div className="panel-pad stack">
              <p className="muted small">
                Send sanitized failures from your server to this endpoint. Use a new UUID and the
                current timestamp for each event.
              </p>
              <pre className="code">{`POST /ingest/v1/events\nX-API-Key: <YOUR_PROJECT_KEY>\nContent-Type: application/json\n\n{\n  "eventId": "<unique-uuid>",\n  "occurredAt": "<current-ISO-timestamp>",\n  "environment": "development",\n  "exceptionType": "IllegalStateException",\n  "message": "Synthetic checkout failure",\n  "stackTrace": "at CheckoutService.submit:42"\n}`}</pre>
              <small className="muted">Successful requests return 202 Accepted.</small>
            </div>
          </Panel>
        </>
      )}
    </>
  );
}
export function StatusPage() {
  const system = useQuery({
    queryKey: ['system'],
    queryFn: ({ signal }) => getSystem(signal),
    refetchInterval: 30000,
  });
  const health = useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => getReadiness(signal),
    refetchInterval: 30000,
  });
  const checking = system.isFetching || health.isFetching;
  const label =
    system.isPending || health.isPending
      ? 'Checking services'
      : system.error || health.error
        ? 'Connection unavailable'
        : health.data?.status === 'UP'
          ? 'Services connected'
          : 'Services need attention';
  return (
    <>
      <Heading
        title="Service status"
        description="Live connection checks for your current environment."
        action={
          <button
            className="button"
            disabled={checking}
            onClick={() => {
              void system.refetch();
              void health.refetch();
            }}
          >
            <RefreshCw size={16} />
            Refresh checks
          </button>
        }
      />
      <div className="notice" role="status">
        {label}
      </div>
      {system.error || health.error ? <Failure error={system.error || health.error} /> : null}
      <Panel title="Application services">
        <div className="panel-pad">
          {[
            {
              name: 'Application API',
              detail: system.data ? `Version ${system.data.version}` : 'Waiting for a response',
              status: system.isSuccess ? 'Connected' : 'Unavailable',
              Icon: Server,
            },
            {
              name: 'Dependency readiness',
              detail: 'Backend-reported database and required dependency health.',
              status: health.data?.status || 'Unknown',
              Icon: Database,
            },
            {
              name: 'Rate limiting',
              detail: system.data?.cacheRequired
                ? 'Redis is required in this environment.'
                : 'Native development uses local rate limits; production uses Redis.',
              status: system.data
                ? system.data.cacheRequired
                  ? 'Redis required'
                  : 'Native mode'
                : 'Unknown',
              Icon: Activity,
            },
            {
              name: 'AI diagnostics',
              detail:
                'Requires configured provider credentials, budgets, and enabled workers. Provider readiness is not reported by this endpoint.',
              status: 'Not reported',
              Icon: Sparkles,
            },
          ].map(({ name, detail, status, Icon }) => (
            <div className="service-row" key={name}>
              <span className="project-icon">
                <Icon size={20} />
              </span>
              <div>
                <h3>{name}</h3>
                <p className="muted small">{detail}</p>
              </div>
              <Badge>{status}</Badge>
            </div>
          ))}
        </div>
      </Panel>
      <Link href="/app/setup" className="text-link">
        Connection guide
        <ArrowRight size={16} />
      </Link>
    </>
  );
}
