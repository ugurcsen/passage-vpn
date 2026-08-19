import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, endpoints, type ServerSettings } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

export function useSettingsMutations() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-settings"] });

  const saveSetting = useMutation({
    mutationFn: ({ key, value }: { key: string; value: unknown }) =>
      api<ServerSettings>(`${endpoints.settings}/${encodeURIComponent(key)}`, {
        method: "PUT",
        body: JSON.stringify({ value }),
      }),
    onSuccess: (updated, vars) => {
      queryClient.setQueryData(["admin-settings"], updated);
      toast.success(`Saved "${vars.key}"`);
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const deleteSetting = useMutation({
    mutationFn: (key: string) =>
      api<void>(`${endpoints.settings}/${encodeURIComponent(key)}`, { method: "DELETE" }),
    onSuccess: (_result, key) => {
      toast.success(`Deleted "${key}"`);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  return {
    saveSetting,
    deleteSetting,
    invalidate,
  };
}
