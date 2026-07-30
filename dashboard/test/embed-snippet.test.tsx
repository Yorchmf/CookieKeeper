import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { EmbedSnippet } from "@/components/sites/embed-snippet";
import en from "../messages/en.json";

const SNIPPET =
  '<script async src="https://cdn.complyr.eu/v1.js" data-site-key="ck_test"></script>';

const writeText = vi.fn<(text: string) => Promise<void>>();

function renderSnippet() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <EmbedSnippet snippet={SNIPPET} />
    </NextIntlClientProvider>,
  );
}

// RTL auto-cleanup needs vitest globals; register it explicitly instead.
afterEach(cleanup);

beforeEach(() => {
  writeText.mockReset();
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText },
    configurable: true,
  });
});

describe("EmbedSnippet", () => {
  test("renders the snippet as plain text, never as HTML", () => {
    renderSnippet();

    // The literal markup must be visible text inside <pre><code> …
    expect(screen.getByText(SNIPPET)).toBeDefined();
    // … and never parsed into a real <script> element.
    expect(document.querySelector("script[data-site-key]")).toBeNull();
  });

  test("copies the snippet and shows transient feedback", async () => {
    writeText.mockResolvedValue(undefined);
    renderSnippet();

    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /copied/i })).toBeDefined();
    });
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(SNIPPET);
  });

  test("keeps the copy label when the clipboard is unavailable", async () => {
    writeText.mockRejectedValue(new Error("denied"));
    renderSnippet();

    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledTimes(1);
    });
    expect(screen.getByRole("button", { name: "Copy" })).toBeDefined();
  });
});
