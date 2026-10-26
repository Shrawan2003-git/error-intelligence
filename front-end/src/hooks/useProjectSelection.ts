import { useWorkspace } from '../workspace';
import { navigate, useLocation } from '../navigation';

export function useProjectSelection() {
  const { projects } = useWorkspace();
  const location = useLocation();
  const requested = new URLSearchParams(location.split('?')[1]).get('project');
  const selected = projects.find((p) => p.id === requested) || projects[0];
  return {
    selected,
    select: (id: string) => {
      const url = new URL(location, window.location.origin);
      url.searchParams.set('project', id);
      navigate(url.pathname + url.search);
    },
  };
}
