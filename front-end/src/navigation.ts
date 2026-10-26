import { useSyncExternalStore } from 'react';

const eventName = 'workspace:navigate';
function subscribe(listener: () => void) {
  window.addEventListener('popstate', listener);
  window.addEventListener(eventName, listener);
  return () => {
    window.removeEventListener('popstate', listener);
    window.removeEventListener(eventName, listener);
  };
}
export function navigate(path: string, replace = false) {
  if (!path.startsWith('/') || path.startsWith('//')) return;
  window.history[replace ? 'replaceState' : 'pushState']({}, '', path);
  window.dispatchEvent(new Event(eventName));
}
export function useLocation() {
  return useSyncExternalStore(subscribe, () => window.location.pathname + window.location.search);
}
export const projectPath = (id: string) => `/app/projects/${encodeURIComponent(id)}`;
export const incidentPath = (id: string) => `/app/incidents/${encodeURIComponent(id)}`;
