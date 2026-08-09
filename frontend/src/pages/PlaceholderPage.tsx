import { Box, Paper, Typography } from "@mui/material";

interface PlaceholderPageProps {
  title: string;
  description: string;
}

/** Temporary page shell for features landing in later phases. */
export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <Box>
      <Typography variant="h5" fontWeight={700} sx={{ mb: 3 }}>
        {title}
      </Typography>
      <Paper sx={{ p: 4 }}>
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      </Paper>
    </Box>
  );
}
