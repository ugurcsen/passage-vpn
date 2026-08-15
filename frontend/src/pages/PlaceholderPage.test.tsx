import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { PlaceholderPage } from "@/pages/PlaceholderPage";

describe("PlaceholderPage", () => {
  it("renders title and description", () => {
    render(
      <ThemeProvider theme={darkTheme}>
        <PlaceholderPage title="Coming soon" description="This feature lands in a later phase." />
      </ThemeProvider>,
    );

    expect(screen.getByRole("heading", { name: "Coming soon" })).toBeInTheDocument();
    expect(screen.getByText("This feature lands in a later phase.")).toBeInTheDocument();
  });
});
