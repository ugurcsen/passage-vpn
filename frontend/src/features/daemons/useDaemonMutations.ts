import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

interface DaemonRequest {
  index: number;
  name: string;
  adminHost: string;
  adminPort: number;
  port: number;
  proto: "udp" | "tcp" | "udp6" | "tcp6";
  subnet: string;
  subnetMask: string;
  ipv6Subnet: string;
  dnsServers: string;
  routes: string;
}

export function useDaemonMutations() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-daemons"] });

  const saveDaemon = useMutation({
    mutationFn: ({ editing, request }: { editing: string | null; request: DaemonRequest }) => {
      if (editing) {
        return api(endpoints.daemons + `/${editing}`, { method: "PUT", body: JSON.stringify(request) });
      }
      return api(endpoints.daemons, { method: "POST", body: JSON.stringify(request) });
    },
    onSuccess: (_data, vars) => {
      toast.success(vars.editing ? "Daemon updated" : "Daemon created");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const toggleEnabled = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      api(endpoints.daemons + `/${id}/${enabled ? "disable" : "enable"}`, { method: "POST" }),
    onSuccess: () => { toast.success("Daemon status updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const deleteDaemon = useMutation({
    mutationFn: (id: string) => api(endpoints.daemons + `/${id}`, { method: "DELETE" }),
    onSuccess: () => { toast.success("Daemon deleted"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  return {
    saveDaemon,
    toggleEnabled,
    deleteDaemon,
    invalidate,
  };
}
