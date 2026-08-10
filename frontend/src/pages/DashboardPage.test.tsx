import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { DashboardPage } from "@/pages/DashboardPage";

describe("DashboardPage", () => {
  it("renders stat cards and phase placeholders", () => {
    render(
      <ThemeProvider theme={darkTheme}>
        <DashboardPage />
      </ThemeProvider>,
    );

    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Active connections")).toBeInTheDocument();
    expect(screen.getByText("Total users")).toBeInTheDocument();
    expect(screen.getByText("Traffic chart (Phase 4)")).toBeInTheDocument();
    expect(screen.getByText("Recent connections (Phase 4)")).toBeInTheDocument();
  });
});
