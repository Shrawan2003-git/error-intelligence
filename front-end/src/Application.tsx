import { useEffect, useRef, useState, type RefObject } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  BookOpen,
  ChartNoAxesCombined,
  ChevronRight,
  ChevronsUpDown,
  CircleDot,
  Folder,
  LayoutDashboard,
  Menu,
  ShieldCheck,
  Users,
  X,
} from 'lucide-react';
import { onSessionChange, restoreSession, type Account } from './api/client';
import { useOrganizationData } from './hooks/useOrganizationData';
import { navigate, useLocation } from './navigation';
import { Link } from './Link';
import { Brand, Failure, Loading } from './ui';
import { initials } from './ui-helpers';
import { WorkspaceContext } from './workspace';
import AuthPage from './features/auth/AuthPage';
import RecoveryPage from './features/auth/RecoveryPage';
import { ProjectsPage, ProjectPage, KeysPage } from './pages/ProjectPages';
import { IncidentsPage, IncidentPage } from './pages/IncidentPages';
import {
  OverviewPage,
  AnalyticsPage,
  AuditPage,
  SetupPage,
  StatusPage,
} from './pages/InsightPages';
import { AccountPage, TeamPage } from './pages/AccountPages';

const navigation = [
  {
    label: 'Workspace',
    items: [
      ['/app', 'Overview', LayoutDashboard],
      ['/app/projects', 'Projects', Folder],
      ['/app/incidents', 'Incidents', CircleDot],
      ['/app/analytics', 'Analytics', ChartNoAxesCombined],
    ],
  },
  {
    label: 'Manage',
    items: [
      ['/app/team', 'Team & access', Users],
      ['/app/audit', 'Audit log', ShieldCheck],
    ],
  },
  {
    label: 'Resources',
    items: [
      ['/app/setup', 'Setup guide', BookOpen],
      ['/app/status', 'Service status', Activity],
    ],
  },
] as const;

const publicPaths = new Set([
  '/login',
  '/register',
  '/check-email',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
]);
const recoveryPaths = new Set([
  '/check-email',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
]);

export default function Application() {
  const location = useLocation(),
    path = location.split('?')[0];
  const [account, setAccount] = useState<Account>();
  const [restoring, setRestoring] = useState(true);
  const [sessionError, setSessionError] = useState<unknown>();
  const queryClient = useQueryClient();
  const main = useRef<HTMLElement>(null);
  useEffect(() => {
    let active = true;
    const unsubscribe = onSessionChange((next) => {
      void queryClient.cancelQueries();
      queryClient.clear();
      if (active) {
        setAccount(next);
        setSessionError(undefined);
      }
    });
    void restoreSession()
      .catch((reason) => {
        if (active) setSessionError(reason);
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [queryClient]);
  useEffect(() => {
    if (restoring) return;
    if (!account && !publicPaths.has(path)) navigate('/login', true);
    if (account && (path === '/' || path === '/login' || path === '/register'))
      navigate('/app', true);
  }, [account, path, restoring]);
  useEffect(() => {
    const title =
      (
        {
          '/register': 'Create account',
          '/login': 'Sign in',
          '/check-email': 'Check email',
          '/verify-email': 'Verify email',
          '/forgot-password': 'Forgot password',
          '/reset-password': 'Reset password',
        } as Record<string, string>
      )[path] ||
      path.split('/')[2]?.replaceAll('-', ' ') ||
      'Overview';
    document.title = `${title.charAt(0).toUpperCase() + title.slice(1)} · Error Intelligence`;
    if (typeof window.scrollTo === 'function' && !/jsdom/i.test(navigator.userAgent))
      window.scrollTo(0, 0);
    main.current?.focus();
  }, [path]);
  if (restoring)
    return (
      <div className="session-loading">
        <Brand />
        <Loading label="Restoring your session…" />
      </div>
    );
  if (recoveryPaths.has(path))
    return (
      <RecoveryPage
        key={path}
        mode={
          path.slice(1) as 'check-email' | 'verify-email' | 'forgot-password' | 'reset-password'
        }
      />
    );
  if (!account)
    return (
      <AuthPage
        key={path}
        mode={path === '/register' ? 'register' : 'login'}
        sessionError={sessionError}
      />
    );
  return (
    <WorkspaceApp
      key={account.activeOrganizationId}
      account={account}
      location={location}
      mainRef={main}
    />
  );
}

function WorkspaceApp({
  account,
  location,
  mainRef,
}: {
  account: Account;
  location: string;
  mainRef: RefObject<HTMLElement | null>;
}) {
  const [navOpen, setNavOpen] = useState(false);
  const organization = account.organizations.find((o) => o.id === account.activeOrganizationId);
  const role = organization?.role ?? 'VIEWER',
    canManage = role === 'OWNER' || role === 'ADMIN';
  const data = useOrganizationData(account.activeOrganizationId),
    queryClient = useQueryClient();
  const path = location.split('?')[0];
  let parts: string[];
  try {
    parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  } catch {
    parts = [];
  }
  const active = parts[1] || 'overview';
  const title =
    (
      {
        overview: 'Overview',
        projects: 'Projects',
        incidents: 'Incidents',
        analytics: 'Analytics',
        team: 'Team & access',
        audit: 'Audit log',
        setup: 'Setup guide',
        account: 'Account & workspaces',
        status: 'Service status',
      } as Record<string, string>
    )[active] || 'Page not found';
  let content;
  if (['team', 'audit'].includes(active) && !canManage)
    content = (
      <div className="panel empty">
        <h1>Access restricted</h1>
        <p>Ask a workspace owner or administrator for access to this page.</p>
        <Link className="button" href="/app">
          Return to overview
        </Link>
      </div>
    );
  else if (active === 'projects' && parts[3] === 'keys')
    content = canManage ? (
      <KeysPage key={parts[2]} projectId={parts[2]} />
    ) : (
      <Failure error={new Error('Only owners and administrators can manage ingestion keys.')} />
    );
  else if (active === 'projects' && parts[2])
    content = <ProjectPage key={parts[2]} projectId={parts[2]} />;
  else if (active === 'projects') content = <ProjectsPage />;
  else if (active === 'incidents' && parts[2])
    content = <IncidentPage key={parts[2]} incidentId={parts[2]} tab={parts[3] || 'evidence'} />;
  else if (active === 'incidents') content = <IncidentsPage />;
  else if (active === 'analytics') content = <AnalyticsPage />;
  else if (active === 'team') content = <TeamPage />;
  else if (active === 'audit') content = <AuditPage />;
  else if (active === 'setup') content = <SetupPage />;
  else if (active === 'status') content = <StatusPage />;
  else if (active === 'account') content = <AccountPage />;
  else if (path === '/app' || path === '/') content = <OverviewPage />;
  else
    content = (
      <div className="panel empty">
        <h1>Page not found</h1>
        <Link href="/app" className="button primary">
          Return to overview
        </Link>
      </div>
    );
  return (
    <WorkspaceContext.Provider
      value={{
        account,
        role,
        canManage,
        canWrite: role !== 'VIEWER',
        projects: data.projects,
        members: data.members,
        loading: data.loading,
        error: data.error,
        refresh: () =>
          queryClient.invalidateQueries({
            queryKey: ['organization', account.activeOrganizationId],
          }),
      }}
    >
      <div className="workspace">
        <a className="skip-link" href="#main-content">
          Skip to content
        </a>
        {navOpen && (
          <button
            className="nav-backdrop"
            onClick={() => setNavOpen(false)}
            aria-label="Close navigation"
          />
        )}
        <aside className={`sidebar ${navOpen ? 'is-open' : ''}`}>
          <div className="sidebar-brand">
            <Link href="/app" onClick={() => setNavOpen(false)}>
              <Brand />
            </Link>
            <button
              className="icon-button mobile-close"
              onClick={() => setNavOpen(false)}
              aria-label="Close menu"
            >
              <X size={20} />
            </button>
          </div>
          <Link href="/app/account" className="workspace-switch" onClick={() => setNavOpen(false)}>
            <span className="avatar">{initials(organization?.name || 'Workspace')}</span>
            <span>
              {organization?.name}
              <small>Workspace · {role.toLowerCase()}</small>
            </span>
            <ChevronsUpDown size={15} />
          </Link>
          {navigation
            .filter((group) => group.label !== 'Manage' || canManage)
            .map((group) => (
              <nav key={group.label} aria-label={group.label}>
                <span className="nav-label">{group.label}</span>
                {group.items.map(([href, label, Icon]) => (
                  <Link
                    key={href}
                    href={href}
                    className={`nav-item ${href === '/app' ? (active === 'overview' ? 'active' : '') : path.startsWith(href) ? 'active' : ''}`}
                    aria-current={
                      (href === '/app' ? path === href : path.startsWith(href)) ? 'page' : undefined
                    }
                    onClick={() => setNavOpen(false)}
                  >
                    <Icon size={17} />
                    {label}
                  </Link>
                ))}
              </nav>
            ))}
          <Link className="sidebar-account" href="/app/account" onClick={() => setNavOpen(false)}>
            <span className="avatar">{initials(account.displayName)}</span>
            <span>
              {account.displayName}
              <small>Account & workspaces</small>
            </span>
          </Link>
        </aside>
        <div className="workspace-main">
          <header className="topbar">
            <div className="breadcrumb">
              <button
                className="icon-button mobile-menu"
                aria-label="Open navigation"
                aria-expanded={navOpen}
                onClick={() => setNavOpen(true)}
              >
                <Menu size={21} />
              </button>
              <span className="muted">Workspace</span>
              <ChevronRight size={14} />
              <span>{title}</span>
            </div>
            <Link className="avatar" href="/app/account" aria-label="Open account">
              {initials(account.displayName)}
            </Link>
          </header>
          <main ref={mainRef} id="main-content" tabIndex={-1} className="page-body" key={path}>
            {data.error && !['account', 'status', 'setup'].includes(active) ? (
              <Failure
                error={data.error}
                retry={() =>
                  void queryClient.invalidateQueries({
                    queryKey: ['organization', account.activeOrganizationId],
                  })
                }
              />
            ) : null}
            {content}
          </main>
          <footer className="workspace-footer">
            <span>Error Intelligence</span>
            <span>Clarity in every exception.</span>
          </footer>
        </div>
      </div>
    </WorkspaceContext.Provider>
  );
}
