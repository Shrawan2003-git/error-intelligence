import { useEffect, useRef, type ReactNode } from 'react';
import { message } from './ui-helpers';
import { Activity, CircleAlert, Inbox, X } from 'lucide-react';

export function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark">
        <Activity size={21} />
      </span>
      <span>Error Intelligence</span>
    </span>
  );
}
export function Heading({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </header>
  );
}
export function Panel({
  title,
  action,
  children,
  className = '',
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {title && (
        <div className="panel-head">
          <h2>{title}</h2>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Notice({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return (
    <div className={`notice ${error ? 'error' : ''}`} role={error ? 'alert' : undefined}>
      <CircleAlert size={17} />
      <div>{children}</div>
    </div>
  );
}
export function Loading({ label = 'Loading workspace data…' }: { label?: string }) {
  return (
    <div className="panel loading" role="status">
      <p>{label}</p>
      <div className="skeleton" />
      <div className="skeleton short" />
      <div className="skeleton tall" />
    </div>
  );
}
export function Empty({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="panel empty">
      <span className="empty-icon">
        <Inbox size={26} />
      </span>
      <h2>{title}</h2>
      <p>{children}</p>
      {action}
    </div>
  );
}
export function Failure({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <Notice error>
      <p>{message(error)}</p>
      {retry && (
        <button className="text-button" onClick={retry}>
          Try again
        </button>
      )}
    </Notice>
  );
}
export function Badge({ children, tone = '' }: { children: ReactNode; tone?: string }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function StatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={status === 'OPEN' ? 'open' : status === 'ACKNOWLEDGED' ? 'ack' : ''}>
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </Badge>
  );
}
export function Modal({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    element?.showModal();
    return () => {
      element?.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog ref={dialog} className="modal" aria-label={title} onCancel={onClose}>
      <div className="modal-heading">
        <h2>{title}</h2>
        <button type="button" className="icon-button" aria-label="Close dialog" onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
