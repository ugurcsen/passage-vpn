import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Box, Button, Paper, Stack, TextField, Typography } from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import SearchIcon from "@mui/icons-material/Search";
import { api, endpoints, type AuditLogEntry, type PageDto } from "@/lib/api";

function formatDateTime(iso: string) {
  return new Date(iso).toLocaleString();
}

function formatDetail(detail: string | null) {
  if (!detail) return "—";
  if (detail.length <= 120) return detail;
  return `${detail.slice(0, 117)}…`;
}

/** Admin audit trail: paginated, filterable log of admin and auth events. */
export function AuditLogsPage() {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [action, setAction] = useState("");
  const [actor, setActor] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [applied, setApplied] = useState({ action: "", actor: "", from: "", to: "" });

  const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
  if (applied.action) params.set("action", applied.action);
  if (applied.actor) params.set("actor", applied.actor);
  if (applied.from) params.set("from", applied.from);
  if (applied.to) params.set("to", applied.to);

  const { data, isLoading } = useQuery<PageDto<AuditLogEntry>>({
    queryKey: ["admin-audit-logs", page, pageSize, applied],
    queryFn: () => api<PageDto<AuditLogEntry>>(`${endpoints.auditLogs}?${params.toString()}`),
  });

  const applyFilters = () => {
    setPage(0);
    setApplied({
      action: action.trim(),
      actor: actor.trim(),
      from: from.trim(),
      to: to.trim(),
    });
  };

  const columns: GridColDef[] = [
    {
      field: "createdAt",
      headerName: "Time",
      width: 190,
      valueFormatter: (value: string) => formatDateTime(value),
    },
    { field: "actorName", headerName: "Actor", width: 160, valueGetter: (_, row) => row.actorName ?? "—" },
    { field: "action", headerName: "Action", width: 180 },
    { field: "category", headerName: "Category", width: 110 },
    { field: "targetType", headerName: "Target", width: 110, valueGetter: (_, row) => row.targetType ?? "—" },
    {
      field: "detail",
      headerName: "Detail",
      flex: 1,
      minWidth: 240,
      valueGetter: (_, row) => formatDetail(row.detail),
    },
    { field: "ip", headerName: "IP", width: 140, valueGetter: (_, row) => row.ip ?? "—" },
  ];

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        Audit log
      </Typography>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 2 }}>
          <TextField
            label="Action"
            size="small"
            value={action}
            onChange={(e) => setAction(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && applyFilters()}
            sx={{ width: 220 }}
          />
          <TextField
            label="Actor"
            size="small"
            value={actor}
            onChange={(e) => setActor(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && applyFilters()}
            sx={{ width: 200 }}
          />
          <TextField
            label="From"
            type="date"
            size="small"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            InputLabelProps={{ shrink: true }}
            sx={{ width: 180 }}
          />
          <TextField
            label="To"
            type="date"
            size="small"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            InputLabelProps={{ shrink: true }}
            sx={{ width: 180 }}
          />
          <Button startIcon={<SearchIcon />} variant="contained" onClick={applyFilters}>
            Apply
          </Button>
        </Stack>
      </Paper>

      <Paper sx={{ p: 2 }}>
        <DataGrid
          rows={data?.content ?? []}
          columns={columns}
          loading={isLoading}
          rowCount={data?.totalElements ?? 0}
          paginationMode="server"
          pageSizeOptions={[25, 50, 100]}
          paginationModel={{ page, pageSize }}
          onPaginationModelChange={(model) => {
            setPage(model.page);
            setPageSize(model.pageSize);
          }}
          disableRowSelectionOnClick
          autoHeight
          getRowHeight={() => "auto"}
          sx={{
            "& .MuiDataGrid-cell": { py: 1, display: "flex", alignItems: "center" },
            "& .MuiDataGrid-columnHeaders": { bgcolor: "action.hover" },
          }}
        />
      </Paper>
    </Box>
  );
}
