import { useEffect, useState, type FormEvent } from 'react';
import { ArrowRight, CircleCheck, Layers, LockKeyhole, MailCheck, ScanSearch } from 'lucide-react';
import {
  confirmVerification,
  requestPasswordReset,
  resendVerification,
  resetPassword,
} from '../../api/client';
import { Link } from '../../Link';
import { Brand, Failure, Notice } from '../../ui';
import { useTask } from '../../ui-helpers';

type Mode = 'check-email' | 'verify-email' | 'forgot-password' | 'reset-password';

const copy: Record<Mode, { eyebrow: string; title: string; description: string }> = {
  'check-email': {
    eyebrow: 'One last step',
    title: 'Check your email',
    description:
      'If this account needs verification, a link is on its way. Check your inbox and spam folder.',
  },
  'verify-email': {
    eyebrow: 'Confirm your address',
    title: 'Verify your email',
    description: 'Confirm your address to finish setting up your account.',
  },
  'forgot-password': {
    eyebrow: 'Account recovery',
    title: 'Forgot your password?',
    description: 'Enter your email and we’ll send a secure reset link if an account exists.',
  },
  'reset-password': {
    eyebrow: 'Account recovery',
    title: 'Choose a new password',
    description: 'Choose a strong new password to regain access to your workspace.',
  },
};

export default function RecoveryPage({ mode }: { mode: Mode }) {
  const [token] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token'));
  const [done, setDone] = useState(false);
  const [localError, setLocalError] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const task = useTask();
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState({}, '', window.location.pathname + window.location.search);
    }
  }, []);
  const needsToken = mode === 'verify-email' || mode === 'reset-password';
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLocalError('');
    const values = new FormData(event.currentTarget);
    const email = String(values.get('email') || '').trim();
    const password = String(values.get('newPassword') || '');
    if (mode === 'reset-password' && password !== String(values.get('confirmPassword') || '')) {
      setLocalError('Passwords do not match.');
      return;
    }
    void task.run(async () => {
      if (mode === 'check-email') await resendVerification(email);
      if (mode === 'forgot-password') await requestPasswordReset(email);
      if (mode === 'verify-email' && token) await confirmVerification(token);
      if (mode === 'reset-password' && token) await resetPassword(token, password);
      setDone(true);
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
          <span className="eyebrow">Secure by design</span>
          <h2>
            YOUR WORK.
            <br />
            YOUR SPACE.
            <br />
            <span>YOUR ACCESS.</span>
          </h2>
          <p>Keep your workspace accessible to the people who belong there.</p>
          <div className="signal-card">
            <div>
              <Layers />
              <span>Protected workspace</span>
            </div>
            <div>
              <ScanSearch />
              <span>Verified account</span>
            </div>
            <div>
              <CircleCheck />
              <span>Secure recovery</span>
            </div>
          </div>
        </div>
        <footer>
          <span>Built for thoughtful engineering teams.</span>
          <span className="story-index">02 / TRUST</span>
        </footer>
      </aside>
      <section className="auth-main">
        <div className="auth-top">
          <Link href="/login">Back to sign in</Link>
        </div>
        <div className="mobile-brand">
          <Brand />
        </div>
        <div className="auth-form">
          <header>
            <span className="eyebrow">{copy[mode].eyebrow}</span>
            <h1>{copy[mode].title}</h1>
            <p>{copy[mode].description}</p>
          </header>
          {task.error ? <Failure error={task.error} /> : null}
          {localError && <Notice error>{localError}</Notice>}
          {done ? (
            <div className="form-stack">
              <Notice>
                <MailCheck size={18} />
                {mode === 'verify-email'
                  ? 'Your email is verified. You can now sign in.'
                  : mode === 'reset-password'
                    ? 'Your password has been changed. Sign in with your new password.'
                    : 'If an eligible account exists, an email is on its way. Check your inbox and spam folder.'}
              </Notice>
              {(mode === 'verify-email' || mode === 'reset-password') && (
                <Link className="button primary full" href="/login">
                  Continue to sign in <ArrowRight size={17} />
                </Link>
              )}
            </div>
          ) : needsToken && !token ? (
            <div className="form-stack">
              <Notice error>This link is incomplete. Request a new one.</Notice>
              <Link
                className="text-link"
                href={mode === 'verify-email' ? '/check-email' : '/forgot-password'}
              >
                Request a new link
              </Link>
            </div>
          ) : (
            <form className="form-stack" onSubmit={submit}>
              {(mode === 'check-email' || mode === 'forgot-password') && (
                <label>
                  Email address
                  <input name="email" type="email" autoComplete="email" maxLength={254} required />
                </label>
              )}
              {mode === 'reset-password' && (
                <>
                  <label>
                    New password
                    <span className="password-field">
                      <input
                        name="newPassword"
                        aria-label="New password"
                        type={showPassword ? 'text' : 'password'}
                        autoComplete="new-password"
                        minLength={12}
                        maxLength={64}
                        required
                      />
                      <button type="button" onClick={() => setShowPassword(!showPassword)}>
                        {showPassword ? 'Hide' : 'Show'}
                      </button>
                    </span>
                  </label>
                  <label>
                    Confirm new password
                    <input
                      name="confirmPassword"
                      type="password"
                      autoComplete="new-password"
                      minLength={12}
                      maxLength={64}
                      required
                    />
                  </label>
                  <small className="muted">Use 12–64 characters.</small>
                </>
              )}
              <button className="button primary full" disabled={task.pending}>
                {task.pending
                  ? 'Please wait…'
                  : mode === 'check-email'
                    ? 'Resend verification email'
                    : mode === 'verify-email'
                      ? 'Verify email'
                      : mode === 'forgot-password'
                        ? 'Send reset link'
                        : 'Reset password'}
                <ArrowRight size={17} />
              </button>
            </form>
          )}
          <p className="auth-security">
            <LockKeyhole size={14} />
            Links expire and can be used only once.
          </p>
        </div>
        <footer className="auth-footer">Error Intelligence · Engineering workspace</footer>
      </section>
    </main>
  );
}
