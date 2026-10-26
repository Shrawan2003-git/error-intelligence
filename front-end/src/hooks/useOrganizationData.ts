import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getMembers, getProjects, type Member, type Project } from '../api/client';

export function useOrganizationData(organizationId?: string) {
  const client = useQueryClient();
  const projectKey = ['organization', organizationId, 'projects'];
  const memberKey = ['organization', organizationId, 'members'];
  const projects = useQuery({
    queryKey: projectKey,
    enabled: Boolean(organizationId),
    queryFn: async ({ signal }) => (await getProjects(signal)).items,
  });
  const members = useQuery({
    queryKey: memberKey,
    enabled: Boolean(organizationId),
    queryFn: ({ signal }) => getMembers(organizationId!, signal),
  });
  return {
    projects: organizationId ? (projects.data ?? []) : [],
    members: organizationId ? (members.data ?? []) : [],
    error: projects.error ?? members.error,
    loading: Boolean(organizationId) && (projects.isPending || members.isPending),
    setProjects: (items: Project[]) => client.setQueryData(projectKey, items),
    setMembers: (items: Member[]) => client.setQueryData(memberKey, items),
    clear: () => {
      void client.cancelQueries({ queryKey: ['organization'] });
      client.removeQueries({ queryKey: ['organization'] });
    },
  };
}
