import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";

import { ButtonLink } from "@/components/ui/button-link";

vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

afterEach(cleanup);

/**
 * `ButtonLink` exists to keep button-*looking* navigation honest about being navigation. Base UI's
 * `<Button render={<Link/>}>` stamps either `type="button"` (a MIME hint on an anchor, plus a dev-time
 * error) or `role="button"` (which overrides the implicit link role — the control then vanishes from a
 * screen reader's links list and misreports itself, WCAG 4.1.2). These tests pin the difference, because
 * the failure is invisible in a browser and reappears the moment someone reaches for `<Button>` again.
 */
describe("ButtonLink", () => {
  test("is exposed as a link, not a button", () => {
    render(<ButtonLink href="/signup">Start free</ButtonLink>);

    const link = screen.getByRole("link", { name: "Start free" });
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("/signup");
    expect(screen.queryByRole("button")).toBeNull();
  });

  test("carries neither of the attributes Base UI's button would stamp on an anchor", () => {
    render(<ButtonLink href="/signup">Start free</ButtonLink>);

    const link = screen.getByRole("link", { name: "Start free" });
    expect(link.hasAttribute("type")).toBe(false);
    expect(link.hasAttribute("role")).toBe(false);
  });

  test("merges a caller className with the variant classes rather than replacing them", () => {
    render(
      <ButtonLink href="/signup" variant="brand" size="lg" className="w-full">
        Start free
      </ButtonLink>,
    );

    const link = screen.getByRole("link", { name: "Start free" });
    expect(link.className).toContain("w-full");
    // A variant class survives alongside it — the two are merged, not overwritten.
    expect(link.className).toContain("inline-flex");
  });

  test("passes arbitrary anchor props through, so an aria-label still reaches the DOM", () => {
    render(
      <ButtonLink href="/sites/1/banner" aria-label="Open banner design">
        Open
      </ButtonLink>,
    );

    expect(screen.getByRole("link", { name: "Open banner design" })).toBeTruthy();
  });
});
