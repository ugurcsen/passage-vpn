import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, endpoints } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import type { DeleteOptions, UserForm } from "./types";

export function useUserMutations() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["admin-users"] });

  const saveUser = useMutation({
    mutationFn: async ({ editing, form }: { editing: string | null; form: UserForm }) => {
      const payload = {
        username: form.username,
        password: form.password || undefined,
        fullName: form.fullName || null,
        email: form.email || null,
        role: form.role,
        groupIds: form.groupIds,
        adminGroupIds: form.role === "GROUP_ADMIN" ? form.adminGroupIds : null,
      };
      if (editing) {
        return api(endpoints.users + `/${editing}`, { method: "PUT", body: JSON.stringify(payload) });
      }
      return api(endpoints.users, { method: "POST", body: JSON.stringify(payload) });
    },
    onSuccess: (_data, vars) => {
      toast.success(vars.editing ? "User updated" : "User created");
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Save failed"),
  });

  const banMutation = useMutation({
    mutationFn: ({ id, banned }: { id: string; banned: boolean }) =>
      api(endpoints.users + `/${id}/${banned ? "unban" : "ban"}`, { method: "POST" }),
    onSuccess: () => { toast.success("User status updated"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Update failed"),
  });

  const resetPassword = useMutation({
    mutationFn: ({ id, password }: { id: string; password: string }) =>
      api(endpoints.users + `/${id}/reset-password`, {
        method: "POST",
        body: JSON.stringify({ password }),
      }),
    onSuccess: () => { toast.success("Password reset"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Reset failed"),
  });

  const deleteSingle = useMutation({
    mutationFn: ({ id, options }: { id: string; options: DeleteOptions }) =>
      api(endpoints.users + `/${id}`, { method: "DELETE", body: JSON.stringify(options) }),
    onSuccess: () => { toast.success("User deleted"); invalidate(); },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Delete failed"),
  });

  const bulkOperation = useMutation({
    mutationFn: ({ action, ids, options }: { action: "ban" | "unban" | "delete"; ids: string[]; options?: DeleteOptions }) =>
      api(endpoints.users + "/bulk", {
        method: "POST",
        body: JSON.stringify({ action: action.toUpperCase(), ids, ...(options ? { options } : {}) }),
      }),
    onSuccess: (_data, vars) => {
      toast.success(`${vars.ids.length} user${vars.ids.length === 1 ? "" : "s"} ${vars.action === "delete" ? "deleted" : vars.action + "ed"}`);
      invalidate();
    },
    onError: (err) => toast.error(err instanceof Error ? err.message : "Bulk operation failed"),
  });

  return {
    saveUser,
    banMutation,
    resetPassword,
    deleteSingle,
    bulkOperation,
    invalidate,
  };
}
