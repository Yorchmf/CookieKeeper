import { createRef } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { ConsentLogBody } from "@/components/consent-log/consent-log";
import { hasActiveConsentFilters } from "@/lib/api/consent";
import en from "../messages/en.json";

// The consent-log module imports `@/i18n/navigation` at load, which resolves next-intl's client
// navigation — unavailable under happy-dom. ConsentLogBody uses none of it, so stub the module.
vi.mock("@/i18n/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({ replace: () => {} }),
}));

afterEach(cleanup);

describe("hasActiveConsentFilters", () => {
  test("is false for an empty filter object", () => {
    expect(hasActiveConsentFilters({})).toBe(false);
  });

  // Each field alone must flip it on, so the empty log never mislabels a filtered miss as "no events yet".
  test.each(["from", "to", "action", "lang", "visitorId"] as const)(
    "is true when only %s is set",
    (field) => {
      expect(hasActiveConsentFilters({ [field]: "x" })).toBe(true);
    },
  );
});

// A minimal query stand-in — ConsentLogBody only reads these four flags in the states under test.
const settledQuery = {
  isPending: false,
  isError: false,
  isFetchingNextPage: false,
  hasNextPage: false,
} as unknown as Parameters<typeof ConsentLogBody>[0]["query"];

function renderBody(
  props: Partial<Parameters<typeof ConsentLogBody>[0]> = {},
) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <ConsentLogBody
        query={props.query ?? settledQuery}
        events={props.events ?? []}
        isFiltered={props.isFiltered ?? false}
        onClearFilters={props.onClearFilters ?? (() => {})}
        sentinelRef={props.sentinelRef ?? createRef<HTMLDivElement>()}
      />
    </NextIntlClientProvider>,
  );
}

describe("ConsentLogBody empty states", () => {
  test("shows the loading skeleton while pending", () => {
    const { container } = renderBody({
      query: { ...settledQuery, isPending: true } as typeof settledQuery,
    });
    // The skeleton block is aria-hidden; assert on it rather than any text.
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(screen.queryByText(en.consentLog.empty.title)).toBeNull();
  });

  test("shows an alert on error", () => {
    renderBody({ query: { ...settledQuery, isError: true } as typeof settledQuery });
    expect(screen.getByRole("alert").textContent).toBe(en.consentLog.loadError);
  });

  test("unfiltered empty log explains that events will appear — no clear-filters action", () => {
    renderBody({ isFiltered: false });

    expect(screen.getByText(en.consentLog.empty.title)).toBeDefined();
    expect(screen.getByText(en.consentLog.empty.description)).toBeDefined();
    // Nothing to clear when no filter is active.
    expect(
      screen.queryByRole("button", { name: en.consentLog.filters.clear }),
    ).toBeNull();
  });

  test("filtered empty log offers a clear-filters action that fires the callback", () => {
    const onClearFilters = vi.fn();
    renderBody({ isFiltered: true, onClearFilters });

    // The distinct "nothing matched" copy, not the generic "no events yet" copy.
    expect(screen.getByText(en.consentLog.empty.filtered.title)).toBeDefined();
    expect(screen.queryByText(en.consentLog.empty.title)).toBeNull();

    fireEvent.click(
      screen.getByRole("button", { name: en.consentLog.filters.clear }),
    );
    expect(onClearFilters).toHaveBeenCalledTimes(1);
  });
});
