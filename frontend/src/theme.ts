import { createTheme, type Theme } from "@mui/material/styles";
import type {} from "@mui/x-data-grid/themeAugmentation";

const palette = {
  secondary: { main: "#26c6a2", light: "#55d9bb", dark: "#1b9277" },
  success: { main: "#4caf50" },
  warning: { main: "#ffb74d" },
  error: { main: "#f44336" },
};

function primary(main: string) {
  return { main, light: main, dark: main };
}

/** Dark theme (default). Mirrors the OpenVPN Access Server look. */
export const darkTheme = createTheme({
  palette: {
    mode: "dark",
    primary: primary("#4f8cff"),
    ...palette,
    background: {
      default: "#0f1520",
      paper: "#17202e",
    },
    text: { primary: "#e6edf6", secondary: "#8fa3bd" },
    divider: "#223049",
  },
  shape: { borderRadius: 8 },
  components: {
    MuiPaper: {
      styleOverrides: { root: { backgroundImage: "none" } },
    },
    MuiAppBar: {
      styleOverrides: { root: { backgroundColor: "#121a29" } },
    },
    MuiDrawer: {
      styleOverrides: { paper: { backgroundColor: "#121a29", borderRight: "1px solid #223049" } },
    },
    MuiDataGrid: {
      styleOverrides: {
        root: { border: "1px solid #223049" },
        columnHeader: {
          backgroundColor: "#141d2e",
          // Hidden sort/column-menu icons otherwise inflate the grid's scroll
          // width and show a useless horizontal scrollbar on every data grid.
          overflow: "hidden",
        },
      },
    },
  },
});

/** Light theme. */
export const lightTheme = createTheme({
  palette: {
    mode: "light",
    primary: primary("#4f8cff"),
    ...palette,
    background: { default: "#f4f6fb", paper: "#ffffff" },
    text: { primary: "#1a2332", secondary: "#5b6b82" },
    divider: "#e0e6ef",
  },
  shape: { borderRadius: 8 },
  components: {
    MuiDataGrid: {
      styleOverrides: {
        root: { border: "1px solid #e0e6ef" },
        columnHeader: {
          // Hidden sort/column-menu icons otherwise inflate the grid's scroll
          // width and show a useless horizontal scrollbar on every data grid.
          overflow: "hidden",
        },
      },
    },
  },
});

/**
 * Builds a dark/light theme with the brand's primary color injected. Derived from the default
 * themes so component overrides stay consistent; the primary palette color is swapped.
 */
export function buildTheme(darkMode: boolean, primaryColor: string): Theme {
  const base = darkMode ? darkTheme : lightTheme;
  return createTheme({
    ...base,
    palette: {
      ...base.palette,
      primary: primary(primaryColor),
    },
  });
}
