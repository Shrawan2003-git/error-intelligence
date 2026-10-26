import { useEffect, useRef, useState } from 'react';

export function useTask() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function run(action: () => Promise<void>) {
    setPending(true);
    setError(undefined);
    try {
      await action();
    } catch (reason) {
      if (mounted.current) setError(reason);
    } finally {
      if (mounted.current) setPending(false);
    }
  }
  return { pending, error, run };
}
export function message(error: unknown) {
  return error instanceof Error
    ? error.message
    : 'The request could not be completed. Please try again.';
}
export function date(value?: string) {
  return value ? new Date(value).toLocaleString() : '—';
}
export function initials(name: string) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase();
}
