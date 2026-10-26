import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Copy, FolderCode, Plus } from 'lucide-react';
import {
  archiveProject,
  createIngestionKey,
  createProject,
  listIngestionKeys,
  revokeIngestionKey,
  type IngestionKey,
} from '../api/client';
import { navigate, projectPath } from '../navigation';
import { Link } from '../Link';
import { Badge, Empty, Failure, Heading, Loading, Modal, Notice, Panel } from '../ui';
import { date, useTask } from '../ui-helpers';
import { useWorkspace } from '../workspace';
import { ProjectInsights } from './InsightPages';

export function CreateProjectButton() {
  const { canManage, refresh } = useWorkspace();
  const [open, setOpen] = useState(false);
  const task = useTask();
  if (!canManage) return null;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    void task.run(async () => {
      const project = await createProject(
        String(values.get('name')).trim(),
        String(values.get('slug')).trim(),
      );
      await refresh();
      setOpen(false);
      navigate(projectPath(project.id));
    });
  }
  return (
    <>
      <button className="button primary" onClick={() => setOpen(true)}>
        <Plus size={16} />
        Create project
      </button>
      {open && (
        <Modal title="Create a project" onClose={() => setOpen(false)}>
          <form className="form-stack" onSubmit={submit}>
            {task.error ? <Failure error={task.error} /> : null}
            <label>
              Project name
              <input name="name" placeholder="e.g. Checkout API" maxLength={120} required />
            </label>
            <label>
              URL slug
              <input
                name="slug"
                placeholder="checkout-api"
                pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                maxLength={80}
                required
              />
              <small>Lowercase letters, numbers, and hyphens.</small>
            </label>
            <button className="button primary" disabled={task.pending}>
              {task.pending ? 'Creating…' : 'Create project'}
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}
export function ProjectsPage() {
  const { projects, loading, error } = useWorkspace();
  return (
    <>
      <Heading
        title="Projects"
        description="A space for every application. A clearer view of every error."
        action={<CreateProjectButton />}
      />
      {loading ? (
        <Loading />
      ) : error ? null : !projects.length ? (
        <Empty title="Your first project starts here" action={<CreateProjectButton />}>
          Create a project, connect your application, and bring its errors into one place.
        </Empty>
      ) : (
        <div className="project-grid">
          {projects.map((project) => (
            <Panel className="project-card" key={project.id}>
              <div className="row spread">
                <span className="project-icon">
                  <FolderCode size={22} />
                </span>
                <Badge>Active</Badge>
              </div>
              <div>
                <h2>{project.name}</h2>
                <p className="mono muted">{project.slug}</p>
              </div>
              <footer>
                <small>Created {new Date(project.createdAt).toLocaleDateString()}</small>
                <Link href={projectPath(project.id)}>
                  Open project <ArrowRight size={15} />
                </Link>
              </footer>
            </Panel>
          ))}
        </div>
      )}
    </>
  );
}
export function ProjectPage({ projectId }: { projectId: string }) {
  const { projects, loading, canManage, refresh, error } = useWorkspace();
  const [confirm, setConfirm] = useState(false);
  const task = useTask();
  const project = projects.find((p) => p.id === projectId);
  if (loading) return <Loading />;
  if (error) return null;
  if (!project)
    return (
      <Empty
        title="Project not found"
        action={
          <Link className="button" href="/app/projects">
            All projects
          </Link>
        }
      >
        This project may be archived or unavailable in this workspace.
      </Empty>
    );
  return (
    <>
      <Link className="back-link" href="/app/projects">
        ← All projects
      </Link>
      <Heading
        title={project.name}
        description={`${project.slug} · Created ${new Date(project.createdAt).toLocaleDateString()}`}
        action={
          <Link
            className="button primary"
            href={`/app/incidents?project=${encodeURIComponent(projectId)}`}
          >
            View incidents
            <ArrowRight size={16} />
          </Link>
        }
      />
      <nav className="page-tabs" aria-label="Project pages">
        <Link className="selected" href={projectPath(projectId)}>
          Overview
        </Link>
        {canManage && <Link href={`${projectPath(projectId)}/keys`}>Ingestion keys</Link>}
        <Link href={`/app/setup?project=${encodeURIComponent(projectId)}`}>Connection guide</Link>
      </nav>
      <ProjectInsights projectId={projectId} />
      {canManage && (
        <Panel className="panel-pad row spread">
          <div>
            <h3>Archive project</h3>
            <p className="muted small">
              Stop new ingestion and remove this project from the active list.
            </p>
          </div>
          <button className="button danger" onClick={() => setConfirm(true)}>
            Archive project
          </button>
        </Panel>
      )}
      {confirm && (
        <Modal title={`Archive ${project.name}?`} onClose={() => setConfirm(false)}>
          <div className="stack">
            <p>
              New events will no longer be accepted for this project. Its incident history is
              retained.
            </p>
            {task.error ? <Failure error={task.error} /> : null}
            <div className="row end">
              <button className="button" onClick={() => setConfirm(false)}>
                Cancel
              </button>
              <button
                className="button danger"
                disabled={task.pending}
                onClick={() =>
                  void task.run(async () => {
                    await archiveProject(projectId);
                    await refresh();
                    navigate('/app/projects');
                  })
                }
              >
                Archive project
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
export function KeysPage({ projectId }: { projectId: string }) {
  const { account, projects, loading } = useWorkspace();
  const client = useQueryClient();
  const project = projects.find((p) => p.id === projectId);
  const queryKey = ['organization', account.activeOrganizationId, 'keys', projectId];
  const keys = useQuery({
    queryKey,
    queryFn: () => listIngestionKeys(projectId),
    enabled: !!project,
  });
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState('');
  const [copied, setCopied] = useState(false);
  const [revoking, setRevoking] = useState<IngestionKey>();
  const task = useTask();
  function close() {
    setCreating(false);
    setSecret('');
    setCopied(false);
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const label = String(new FormData(event.currentTarget).get('label')).trim();
    void task.run(async () => {
      const result = await createIngestionKey(projectId, label);
      setSecret(result.secret);
      await client.invalidateQueries({ queryKey });
    });
  }
  if (loading) return <Loading />;
  if (!project)
    return <Empty title="Project unavailable">Select an active project to manage its keys.</Empty>;
  return (
    <>
      <Link className="back-link" href={projectPath(projectId)}>
        ← {project.name}
      </Link>
      <Heading
        title="Ingestion keys"
        description={`${project.name} · Connect your application securely.`}
        action={
          <button className="button primary" onClick={() => setCreating(true)}>
            <Plus size={16} />
            Create key
          </button>
        }
      />
      <Notice>A new key is shown only once. Store it with your application’s secrets.</Notice>
      {keys.isPending ? (
        <Loading />
      ) : keys.error ? (
        <Failure error={keys.error} retry={() => void keys.refetch()} />
      ) : !keys.data.length ? (
        <Empty
          title="Connect your application"
          action={
            <button className="button primary" onClick={() => setCreating(true)}>
              Create ingestion key
            </button>
          }
        >
          Create a project key to securely send errors from your application.
        </Empty>
      ) : (
        <Panel title="Project keys">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Key</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {keys.data.map((key) => (
                  <tr key={key.id}>
                    <td>
                      <strong>{key.label}</strong>
                      <span className="secondary mono">
                        {key.prefix}•••••• · {key.scope}
                      </span>
                    </td>
                    <td>
                      <Badge tone={key.revokedAt ? 'ack' : ''}>
                        {key.revokedAt ? 'Revoked' : 'Active'}
                      </Badge>
                    </td>
                    <td>{date(key.createdAt)}</td>
                    <td>
                      {!key.revokedAt && (
                        <button className="text-button danger" onClick={() => setRevoking(key)}>
                          Revoke<span className="sr-only"> {key.label}</span>
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      <Link className="text-link" href={`/app/setup?project=${encodeURIComponent(projectId)}`}>
        How to send your first error <ArrowRight size={15} />
      </Link>
      {creating && (
        <Modal title={secret ? 'Key created' : 'Create ingestion key'} onClose={close}>
          {secret ? (
            <div className="stack">
              <Notice>Copy your key now. You won’t be able to see it again.</Notice>
              <code className="secret-value">{secret}</code>
              <button
                className="button"
                onClick={() =>
                  void task.run(async () => {
                    await navigator.clipboard.writeText(secret);
                    setCopied(true);
                  })
                }
              >
                <Copy size={16} />
                {copied ? 'Copied' : 'Copy key'}
              </button>
              {task.error ? <Failure error={task.error} /> : null}
              <button className="button primary" onClick={close}>
                Done
              </button>
            </div>
          ) : (
            <form className="form-stack" onSubmit={submit}>
              {task.error ? <Failure error={task.error} /> : null}
              <label>
                Key label
                <input name="label" placeholder="e.g. Production sender" maxLength={100} required />
              </label>
              <button className="button primary" disabled={task.pending}>
                {task.pending ? 'Creating…' : 'Create key'}
              </button>
            </form>
          )}
        </Modal>
      )}
      {revoking && (
        <Modal title={`Revoke ${revoking.label}?`} onClose={() => setRevoking(undefined)}>
          <div className="stack">
            <p>Applications using this key will no longer be able to send events.</p>
            {task.error ? <Failure error={task.error} /> : null}
            <button
              className="button danger"
              disabled={task.pending}
              onClick={() =>
                void task.run(async () => {
                  await revokeIngestionKey(projectId, revoking.id);
                  await client.invalidateQueries({ queryKey });
                  setRevoking(undefined);
                })
              }
            >
              Revoke key
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
