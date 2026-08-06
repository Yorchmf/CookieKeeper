import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { LockedFeature } from "@/components/ui/locked-feature";
import en from "../messages/en.json";

// `@/i18n/navigation` re-exports next-intl's client navigation, which resolves `next/navigation` at
// module load — unavailable under happy-dom. Swap `Link` for a plain anchor so the unit under test
// (the locked-control semantics) renders without dragging in the Next.js router.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

const REASON = "On-demand rescans are available on the Pro and Business plans.";

function renderLocked() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <LockedFeature label="Re-scan now" reason={REASON} />
    </NextIntlClientProvider>,
  );
}

// RTL auto-cleanup needs vitest globals; register it explicitly instead.
afterEach(cleanup);

describe("LockedFeature", () => {
  test("never uses the bare disabled attribute — it would drop the control from the tab order", () => {
    renderLocked();

    const button = screen.getByRole("button", { name: /re-scan now/i });
    // `disabled` removes the element from the accessibility tree and tab order, hiding the upsell
    // from keyboard and screen-reader users. `aria-disabled` keeps it focusable and announced.
    expect(button.hasAttribute("disabled")).toBe(false);
    expect(button.getAttribute("aria-disabled")).toBe("true");
  });

  test("stays reachable by role and focusable so AT users can discover why it's locked", () => {
    renderLocked();

    const button = screen.getByRole("button", { name: /re-scan now/i });
    button.focus();
    expect(document.activeElement).toBe(button);
  });

  test("associates the reason via aria-describedby, satisfying WCAG 3.3.2 for AT users", () => {
    renderLocked();

    const button = screen.getByRole("button", { name: /re-scan now/i });
    const describedBy = button.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();

    const reason = document.getElementById(describedBy as string);
    expect(reason).not.toBeNull();
    expect(reason?.textContent).toContain(REASON);
  });

  test("exposes a reachable upgrade link inside the reason", () => {
    renderLocked();

    expect(screen.getByRole("link", { name: /upgrade/i })).toBeDefined();
  });

  test("clicking the locked control prevents the default action", () => {
    renderLocked();

    // The locked button calls preventDefault on its own click, so the browser default (form submit,
    // navigation) never runs — a locked control is inert by construction, not merely styled as such.
    const button = screen.getByRole("button", { name: /re-scan now/i });
    const clickEvent = new MouseEvent("click", { bubbles: true, cancelable: true });
    button.dispatchEvent(clickEvent);
    expect(clickEvent.defaultPrevented).toBe(true);
  });
});
