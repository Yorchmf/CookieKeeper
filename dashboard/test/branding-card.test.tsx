import type { ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { BrandingCard } from "@/components/sites/branding-card";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; swap
// Link for a plain anchor so the gated-switch semantics render without the Next.js router.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// Stub the mutation hook so the card renders without a QueryClientProvider or a real network call; the
// spy lets each test assert whether a toggle actually fired.
const mutateAsync = vi.fn().mockResolvedValue(undefined);
vi.mock("@/hooks/use-sites", () => ({
  useSetSiteBranding: () => ({ mutateAsync, isPending: false }),
}));

function renderCard(props: { hideBranding: boolean; isEntitled: boolean }) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <BrandingCard siteId="site-1" {...props} />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  mutateAsync.mockClear();
});

describe("BrandingCard", () => {
  test("reflects the stored preference on the switch regardless of plan", () => {
    renderCard({ hideBranding: true, isEntitled: false });

    // Even when the plan can't remove branding, the switch shows the customer's saved wish.
    expect(screen.getByRole("switch").getAttribute("aria-checked")).toBe("true");
  });

  test("when not entitled the switch is aria-disabled, focusable, and points at the reason", () => {
    renderCard({ hideBranding: false, isEntitled: false });

    const toggle = screen.getByRole("switch");
    // Never the bare disabled attribute — that drops the upsell from the tab order and AT tree.
    expect(toggle.hasAttribute("disabled")).toBe(false);
    expect(toggle.getAttribute("aria-disabled")).toBe("true");

    toggle.focus();
    expect(document.activeElement).toBe(toggle);

    const describedBy = toggle.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy as string)).not.toBeNull();
    expect(screen.getByRole("link", { name: /upgrade/i })).toBeDefined();
  });

  test("a locked toggle never fires the mutation", () => {
    renderCard({ hideBranding: false, isEntitled: false });

    fireEvent.click(screen.getByRole("switch"));

    expect(mutateAsync).not.toHaveBeenCalled();
  });

  test("an entitled toggle persists the inverted preference", () => {
    renderCard({ hideBranding: false, isEntitled: true });

    const toggle = screen.getByRole("switch");
    expect(toggle.getAttribute("aria-disabled")).toBe("false");
    expect(toggle.getAttribute("aria-describedby")).toBeNull();

    fireEvent.click(toggle);

    expect(mutateAsync).toHaveBeenCalledWith(true);
  });
});
