import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

interface GroupRequest {
  name: string;
  description: string;
  parentId: string | null;
}

export function useGroupMutations() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-groups"] });

  const saveGroup = useMutation({
    mutationFn: ({ editing, request }: { editing: string | null; request: GroupRequest }) => {
      if (editing) {
        return api(endpoints.groups + `/${editing}`, { method: "PUT", body: JSON.stringify(request) });
      }
      return api(endpoints.groups, { method: "POST", body: JSON.stringify(request) });
    },
    onSuccess: (_data, vars) => {
      toast.success(vars.editing ? "Group updated" : "Group created");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const deleteGroup = useMutation({
    mutationFn: (id: string) => api(endpoints.groups + `/${id}`, { method: "DELETE" }),
    onSuccess: () => { toast.success("Group deleted"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const updateMembers = useMutation({
    mutationFn: ({ id, memberIds }: { id: string; memberIds: string[] }) =>
      api(endpoints.groups + `/${id}/members`, { method: "PUT", body: JSON.stringify({ memberIds }) }),
    onSuccess: () => { toast.success("Members updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const updatePool = useMutation({
    mutationFn: ({ id, cidr }: { id: string; cidr: string }) =>
      api(endpoints.groups + `/${id}/pool`, { method: "PUT", body: JSON.stringify({ cidr }) }),
    onSuccess: () => { toast.success("IPv4 pool updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const updatePoolIpv6 = useMutation({
    mutationFn: ({ id, cidr }: { id: string; cidr: string }) =>
      api(endpoints.groups + `/${id}/pool-ipv6`, { method: "PUT", body: JSON.stringify({ cidr }) }),
    onSuccess: () => { toast.success("IPv6 pool updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  return {
    saveGroup,
    deleteGroup,
    updateMembers,
    updatePool,
    updatePoolIpv6,
    invalidate,
  };
}
