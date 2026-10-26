import { createContext, useContext } from 'react';
import type { Account, Member, Project } from './api/client';

export interface Workspace {
  account: Account;
  projects: Project[];
  members: Member[];
  loading: boolean;
  error: unknown;
  role: Member['role'];
  canManage: boolean;
  canWrite: boolean;
  refresh: () => Promise<void>;
}
export const WorkspaceContext = createContext<Workspace | null>(null);
export function useWorkspace() {
  const value = useContext(WorkspaceContext);
  if (!value) throw new Error('Workspace provider is missing.');
  return value;
}
