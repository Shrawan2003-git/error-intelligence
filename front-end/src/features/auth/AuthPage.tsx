import { useState, type FormEvent } from 'react';
import { ArrowRight, CircleCheck, Layers, LockKeyhole, ScanSearch } from 'lucide-react';
import { ApiError, signIn, signUp } from '../../api/client';
import { navigate } from '../../navigation';
import { Link } from '../../Link';
import { Brand, Failure } from '../../ui';
import { useTask } from '../../ui-helpers';

export default function AuthPage({
  mode,
  sessionError,
}: {
  mode: 'login' | 'register';
  sessionError?: unknown;
}) {
  const register = mode === 'register';
  const [visible, setVisible] = useState(false);
  const task = useTask();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    void task.run(async () => {
      const email = String(values.get('email')).trim(),
        password = String(values.get('password'));
      if (register) {
        await signUp(
          email,
          password,
          String(values.get('displayName')).trim(),
          String(values.get('organizationName')).trim(),
        );
        navigate('/check-email', true);
      } else {
        await signIn(email, password);
        navigate('/app', true);
      }
    });
  }
  return (
    <main className="auth-page">
      <aside className="auth-story">
        <Brand />
        <div className="signal-orbit" aria-hidden="true">
          <div className="orbit-ring ring-one" />
          <div className="orbit-ring ring-two" />
          <div className="orbit-ring ring-three" />
          <div className="orbit-core">
            <span>EI</span>
          </div>
          <span className="orbit-label">SIGNAL / FOUND</span>
        </div>
        <div className="auth-copy">
          <span className="eyebrow">From error to understanding</span>
          <h2>
            Less noise.
            <br />
            More clarity.
            <br />
            <span>Better fixes.</span>
          </h2>
          <p>
            Bring application errors, investigation, and verified resolutions into one workspace.
          </p>
          <div className="signal-card">
            <div>
              <Layers />
              <span>Group repeated errors</span>
            </div>
            <div>
              <ScanSearch />
              <span>Investigate with evidence</span>
            </div>
            <div>
              <CircleCheck />
              <span>Keep the fixes that work</span>
            </div>
          </div>
        </div>
        <footer>
          <span>Built for thoughtful engineering teams.</span>
          <span className="story-index">01 / CLARITY</span>
        </footer>
      </aside>
      <section className="auth-main">
        <div className="auth-top">
          <span>{register ? 'Already have an account?' : 'New to Error Intelligence?'}</span>
          <Link href={register ? '/login' : '/register'}>
            {register ? 'Sign in' : 'Create an account'}
          </Link>
        </div>
        <div className="mobile-brand">
          <Brand />
        </div>
        <div className="auth-form">
          <header>
            <span className="eyebrow">{register ? 'Your new workspace' : 'Welcome back'}</span>
            <h1>{register ? 'Create your account' : 'Sign in to your workspace'}</h1>
            <p>
              {register
                ? 'A place for your team to understand and resolve errors.'
                : 'Pick up where your investigation left off.'}
            </p>
          </header>
          {task.error || sessionError ? <Failure error={task.error || sessionError} /> : null}
          {task.error instanceof ApiError && task.error.code === 'EMAIL_NOT_VERIFIED' && (
            <Link className="text-link" href="/check-email">
              Resend verification email
            </Link>
          )}
          <form onSubmit={submit} className="form-stack">
            {register && (
              <>
                <label>
                  Full name
                  <input
                    name="displayName"
                    autoComplete="name"
                    placeholder="e.g. Shrawan Gautam"
                    maxLength={100}
                    required
                  />
                </label>
                <label>
                  Workspace name
                  <input
                    name="organizationName"
                    autoComplete="organization"
                    placeholder="e.g. Engineering team"
                    maxLength={120}
                    required
                  />
                </label>
              </>
            )}
            <label>
              Email address
              <input
                name="email"
                type="email"
                autoComplete="email"
                placeholder="you@company.com"
                maxLength={254}
                required
              />
            </label>
            <label htmlFor="auth-password">
              Password
              <span className="password-field">
                <input
                  id="auth-password"
                  aria-label="Password"
                  name="password"
                  type={visible ? 'text' : 'password'}
                  autoComplete={register ? 'new-password' : 'current-password'}
                  placeholder={register ? 'At least 12 characters' : 'Enter your password'}
                  minLength={register ? 12 : 1}
                  maxLength={64}
                  required
                />
                <button
                  type="button"
                  onClick={() => setVisible(!visible)}
                  aria-label={visible ? 'Hide password' : 'Show password'}
                >
                  {visible ? 'Hide' : 'Show'}
                </button>
              </span>
              {register && <small>Use at least 12 characters.</small>}
            </label>
            {!register && (
              <div className="auth-assist">
                <Link href="/forgot-password">Forgot password?</Link>
              </div>
            )}
            <button className="button primary full" disabled={task.pending}>
              {task.pending ? 'Connecting…' : register ? 'Create workspace' : 'Sign in'}
              <ArrowRight size={17} />
            </button>
          </form>
          {register && (
            <p className="muted small">We’ll email you a link to verify your account.</p>
          )}
          <p className="auth-security">
            <LockKeyhole size={14} />
            Your team’s work stays in your workspace.
          </p>
        </div>
        <footer className="auth-footer">Error Intelligence · Engineering workspace</footer>
      </section>
    </main>
  );
}
