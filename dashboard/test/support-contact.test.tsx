import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeAll, describe, expect, test, vi } from "vitest";

import { FaqSection } from "@/components/marketing/faq-section";
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from "@/lib/site";
import en from "../messages/en.json";

// Reveal wraps its children in an IntersectionObserver; happy-dom has none, so stub it to a no-op that
// never fires (the children render immediately regardless of intersection).
beforeAll(() => {
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  );
});

afterEach(cleanup);

describe("support contact constants", () => {
  test("default address and its mailto derivation stay in lockstep", () => {
    expect(SUPPORT_EMAIL).toBe("support@complyr.eu");
    expect(SUPPORT_MAILTO).toBe(`mailto:${SUPPORT_EMAIL}`);
  });
});

describe("FaqSection contact link", () => {
  test("makes the 'a real human will reply' promise real with a mailto link", () => {
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <FaqSection />
      </NextIntlClientProvider>,
    );

    // A link (not just text) that actually opens a message to the support inbox.
    const link = screen.getByRole("link", {
      name: en.marketing.faq.contactCta.replace("{email}", SUPPORT_EMAIL),
    });
    expect(link.getAttribute("href")).toBe(SUPPORT_MAILTO);
  });
});
