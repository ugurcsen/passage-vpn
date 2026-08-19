import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@mui/material/styles";
import { darkTheme } from "@/theme";
import { ConfirmDialog } from "@/components/ConfirmDialog";

function renderDialog(overrides: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  return render(
    <ThemeProvider theme={darkTheme}>
      <ConfirmDialog
        open
        title="Delete user?"
        message="This action cannot be undone."
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        {...overrides}
      />
    </ThemeProvider>,
  );
}

describe("ConfirmDialog", () => {
  it("renders with title and message", () => {
    renderDialog();
    expect(screen.getByText("Delete user?")).toBeInTheDocument();
    expect(screen.getByText("This action cannot be undone.")).toBeInTheDocument();
  });

  it("calls onConfirm when confirm button is clicked", async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderDialog({ onConfirm });

    await user.click(screen.getByRole("button", { name: /confirm/i }));

    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("calls onCancel when cancel button is clicked", async () => {
    const onCancel = vi.fn();
    const user = userEvent.setup();
    renderDialog({ onCancel });

    await user.click(screen.getByRole("button", { name: /cancel/i }));

    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("uses error color when danger is true", () => {
    renderDialog({ danger: true });
    const confirmBtn = screen.getByRole("button", { name: /confirm/i });
    expect(confirmBtn).toHaveClass("MuiButton-colorError");
  });

  it("uses primary color when danger is false", () => {
    renderDialog({ danger: false });
    const confirmBtn = screen.getByRole("button", { name: /confirm/i });
    expect(confirmBtn).toHaveClass("MuiButton-colorPrimary");
  });
});
