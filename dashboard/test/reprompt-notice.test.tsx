import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test } from "vitest";

import { RepromptNotice } from "@/components/analytics/reprompt-notice";
import type { ConsentRepromptNotice } from "@/lib/api/analytics";
import en from "../messages/en.json";

/**
 * The re-prompt notice (BACKLOG #18) exists to keep one promise: a customer must never see the impression
 * count and interaction rate step without being told the step is ours. So what is asserted here is that the
 * caveat is always present, the date is stated, and the categories are named in the customer's language
 * rather than as raw taxonomy keys.
 */
function renderNotice(notice: Partial<ConsentRepromptNotice> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <RepromptNotice
        notice={{
          changedAt: "2026-03-14T10:00:00Z",
          addedCategories: ["marketing"],
          ...notice,
        }}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe("RepromptNotice", () => {
  test("names the categories that caused the re-prompt and when it happened", () => {
    renderNotice({ addedCategories: ["marketing", "statistics"] });

    // Translated labels, not the wire keys — the customer never sees our taxonomy spelling.
    expect(screen.getByRole("heading").textContent).toContain("Marketing and Statistics");
    expect(screen.getByText(/Mar 14, 2026/)).toBeTruthy();
  });

  test("warns that the analytics step is the re-prompt, not the customer's traffic", () => {
    renderNotice();

    expect(screen.getByText(/not a change in your traffic/)).toBeTruthy();
  });

  test("still explains itself when no category was recorded", () => {
    // Rows written before the reason was stored: the caveat matters more than the cause.
    renderNotice({ addedCategories: [] });

    expect(screen.getByRole("heading").textContent).toBe("Visitors were asked for consent again");
    expect(screen.getByText(/not a change in your traffic/)).toBeTruthy();
  });

  test("passes an unknown category key through rather than rendering an error", () => {
    renderNotice({ addedCategories: ["social"] });

    expect(screen.getByRole("heading").textContent).toContain("social");
  });
});
