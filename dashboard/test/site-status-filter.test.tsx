import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  parseSiteStatus,
  SiteStatusFilter,
} from "@/components/sites/site-status-filter";
import en from "../messages/en.json";

function renderFilter(props?: {
  value?: "active" | "archived";
  onChange?: (s: "active" | "archived") => void;
}) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <SiteStatusFilter
        value={props?.value ?? "active"}
        onChange={props?.onChange ?? (() => {})}
      />
    </NextIntlClientProvider>,
  );
}

function radios(): HTMLElement[] {
  return screen.getAllByRole("radio");
}

afterEach(cleanup);

describe("parseSiteStatus", () => {
  test("passes through the supported statuses", () => {
    expect(parseSiteStatus("active")).toBe("active");
    expect(parseSiteStatus("archived")).toBe("archived");
  });

  test("falls back to active for anything unrecognized", () => {
    expect(parseSiteStatus(null)).toBe("active");
    expect(parseSiteStatus(undefined)).toBe("active");
    expect(parseSiteStatus("")).toBe("active");
    expect(parseSiteStatus("deleted")).toBe("active");
    // A crafted value must never be echoed back as a status.
    expect(parseSiteStatus("../../etc")).toBe("active");
  });
});

describe("SiteStatusFilter", () => {
  test("exposes a labeled radiogroup with the checked option as the only tab stop", () => {
    renderFilter({ value: "archived" });

    expect(screen.getByRole("radiogroup")).toBeDefined();
    const [active, archived] = radios();
    expect(active.getAttribute("aria-checked")).toBe("false");
    expect(active.getAttribute("tabindex")).toBe("-1");
    expect(archived.getAttribute("aria-checked")).toBe("true");
    expect(archived.getAttribute("tabindex")).toBe("0");
  });

  test("clicking an option reports the new status", () => {
    const onChange = vi.fn();
    renderFilter({ value: "active", onChange });

    fireEvent.click(screen.getByRole("radio", { name: "Archived" }));

    expect(onChange).toHaveBeenCalledWith("archived");
  });

  test("ArrowRight moves selection to the next option", () => {
    const onChange = vi.fn();
    renderFilter({ value: "active", onChange });

    fireEvent.keyDown(radios()[0], { key: "ArrowRight" });

    expect(onChange).toHaveBeenCalledWith("archived");
  });

  test("ArrowLeft wraps from the first option to the last", () => {
    const onChange = vi.fn();
    renderFilter({ value: "active", onChange });

    fireEvent.keyDown(radios()[0], { key: "ArrowLeft" });

    expect(onChange).toHaveBeenCalledWith("archived");
  });
});
