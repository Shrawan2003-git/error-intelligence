import { useState, type FormEvent } from 'react';
import { LogOut, Plus, UserPlus } from 'lucide-react';
import {
  addMember,
  createOrganization,
  removeMember,
  signOut,
  switchOrganization,
  updateMemberRole,
  type Member,
} from '../api/client';
import { navigate } from '../navigation';
import { Badge, Failure, Heading, Loading, Modal, Notice, Panel } from '../ui';
import { initials, useTask } from '../ui-helpers';
import { useWorkspace } from '../workspace';

export function AccountPage() {
  const { account } = useWorkspace(),
    task = useTask();
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = String(new FormData(event.currentTarget).get('organization')).trim();
    void task.run(async () => {
      const org = await createOrganization(name);
      await switchOrganization(org.id);
      navigate('/app');
    });
  }
  return (
    <>
      <Heading
        title="Account & workspaces"
        description="Your profile. Your teams. Your place to do good work."
        action={
          <button
            className="button"
            disabled={task.pending}
            onClick={() =>
              void task.run(async () => {
                await signOut();
                navigate('/login', true);
              })
            }
          >
            <LogOut size={16} />
            Sign out
          </button>
        }
      />
      {task.error ? <Failure error={task.error} /> : null}
      <Panel title="Your account">
        <div className="panel-pad row">
          <span className="avatar large">{initials(account.displayName)}</span>
          <div>
            <h2>{account.displayName}</h2>
            <p className="muted small">{account.email}</p>
          </div>
        </div>
      </Panel>
      <Panel title="Your workspaces">
        <div className="panel-pad">
          {account.organizations.map((org) => (
            <div className="service-row" key={org.id}>
              <span className="project-icon">{initials(org.name)}</span>
              <div>
                <h3>{org.name}</h3>
                <p className="muted small">{org.role.toLowerCase()}</p>
              </div>
              {org.id === account.activeOrganizationId ? (
                <Badge>Current</Badge>
              ) : (
                <button
                  className="button"
                  aria-label={`Switch to ${org.name}`}
                  disabled={task.pending}
                  onClick={() =>
                    void task.run(async () => {
                      await switchOrganization(org.id);
                      navigate('/app');
                    })
                  }
                >
                  Switch<span className="sr-only"> to {org.name}</span>
                </button>
              )}
            </div>
          ))}
        </div>
      </Panel>
      <Panel title="Create a workspace">
        <form className="panel-pad form-stack constrained" onSubmit={create}>
          <p className="muted small">Give another team or project group its own workspace.</p>
          <label>
            Workspace name
            <input name="organization" placeholder="e.g. Platform team" maxLength={120} required />
          </label>
          <div>
            <button className="button primary" disabled={task.pending}>
              <Plus size={16} />
              Create workspace
            </button>
          </div>
        </form>
      </Panel>
    </>
  );
}
export function TeamPage() {
  const { account, members, role, loading, refresh } = useWorkspace(),
    task = useTask();
  const [add, setAdd] = useState(false),
    [selected, setSelected] = useState<Member>(),
    [confirmRemove, setConfirmRemove] = useState(false);
  const roles: Member['role'][] =
    role === 'OWNER' ? ['DEVELOPER', 'VIEWER', 'ADMIN', 'OWNER'] : ['DEVELOPER', 'VIEWER', 'ADMIN'];
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    void task.run(async () => {
      await addMember(
        account.activeOrganizationId,
        String(values.get('email')).trim(),
        String(values.get('role')) as Member['role'],
      );
      await refresh();
      setAdd(false);
    });
  }
  function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = String(new FormData(event.currentTarget).get('role')) as Member['role'];
    if (!selected) return;
    void task.run(async () => {
      await updateMemberRole(account.activeOrganizationId, selected.userId, next);
      await refresh();
      setSelected(undefined);
    });
  }
  return (
    <>
      <Heading
        title="Team & access"
        description="Bring the right people into the investigation."
        action={
          <button className="button primary" onClick={() => setAdd(true)}>
            <UserPlus size={16} />
            Add member
          </button>
        }
      />
      <Notice>
        Add people who already have an account. Email invitations are not available yet.
      </Notice>
      {loading ? (
        <Loading />
      ) : (
        <Panel title="Workspace members">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Member</th>
                  <th>Role</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {members.map((member) => (
                  <tr key={member.userId}>
                    <td>
                      <div className="row">
                        <span className="avatar">{initials(member.displayName)}</span>
                        <div>
                          <strong>{member.displayName}</strong>
                          <span className="secondary">{member.email}</span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <Badge>{member.role.toLowerCase()}</Badge>
                    </td>
                    <td>
                      {member.userId === account.id ? (
                        <small className="muted">You</small>
                      ) : member.role !== 'OWNER' || role === 'OWNER' ? (
                        <button
                          className="button"
                          onClick={() => {
                            setSelected(member);
                            setConfirmRemove(false);
                          }}
                        >
                          Manage<span className="sr-only"> {member.displayName}</span>
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      <Panel title="Role permissions">
        <div className="panel-pad stack">
          <div>
            <h3>Owner & Admin</h3>
            <p className="muted small">
              Manage projects, keys, members, and audit history. Owners manage ownership.
            </p>
          </div>
          <div>
            <h3>Developer</h3>
            <p className="muted small">
              Investigate incidents, add comments, and verify resolutions.
            </p>
          </div>
          <div>
            <h3>Viewer</h3>
            <p className="muted small">Browse project information and incident history.</p>
          </div>
        </div>
      </Panel>
      {add && (
        <Modal title="Add a member" onClose={() => setAdd(false)}>
          <form className="form-stack" onSubmit={submit}>
            {task.error ? <Failure error={task.error} /> : null}
            <label>
              Registered account email
              <input name="email" type="email" maxLength={254} required />
            </label>
            <label>
              Role
              <select name="role">
                {roles.map((value) => (
                  <option value={value} key={value}>
                    {value.toLowerCase()}
                  </option>
                ))}
              </select>
            </label>
            <button className="button primary" disabled={task.pending}>
              Add member
            </button>
          </form>
        </Modal>
      )}
      {selected && (
        <Modal
          title={
            confirmRemove ? `Remove ${selected.displayName}?` : `Manage ${selected.displayName}`
          }
          onClose={() => setSelected(undefined)}
        >
          {task.error ? <Failure error={task.error} /> : null}
          {confirmRemove ? (
            <div className="stack">
              <p>This person will lose access to this workspace.</p>
              <button
                className="button danger"
                disabled={task.pending}
                onClick={() =>
                  void task.run(async () => {
                    await removeMember(account.activeOrganizationId, selected.userId);
                    await refresh();
                    setSelected(undefined);
                  })
                }
              >
                Remove member
              </button>
            </div>
          ) : (
            <form className="form-stack" onSubmit={update}>
              <label>
                Role
                <select name="role" defaultValue={selected.role}>
                  {roles.map((value) => (
                    <option value={value} key={value}>
                      {value.toLowerCase()}
                    </option>
                  ))}
                </select>
              </label>
              <button className="button primary" disabled={task.pending}>
                Save role
              </button>
              <button
                className="text-button danger"
                type="button"
                onClick={() => setConfirmRemove(true)}
              >
                Remove from workspace
              </button>
            </form>
          )}
        </Modal>
      )}
    </>
  );
}
