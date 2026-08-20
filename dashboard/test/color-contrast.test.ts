import { describe, expect, test } from "vitest";
import {
  AA_NON_TEXT,
  AA_NORMAL_TEXT,
  contrastRatio,
} from "@/lib/color-contrast";

/**
 * These assertions are deliberately the same ones `ColorContrastTest.kt` makes. The customizer's
 * readout is only useful if it agrees with the validator that actually returns the 400 (ADR-28), so
 * the two implementations are pinned to identical reference values rather than to each other.
 */
describe("contrastRatio", () => {
  test("matches the WCAG reference extremes", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#7f7f7f", "#7f7f7f")).toBeCloseTo(1, 5);
  });

  test("does not depend on the order of the pair", () => {
    expect(contrastRatio("#1f2430", "#f5f6f8")).toBeCloseTo(
      contrastRatio("#f5f6f8", "#1f2430")!,
      10,
    );
  });

  test("expands three-digit shorthand", () => {
    expect(contrastRatio("#fff", "#000")).toBeCloseTo(
      contrastRatio("#ffffff", "#000000")!,
      10,
    );
  });

  test("returns null rather than a passing number for a non-colour", () => {
    // The hex field is free text, so half-typed values arrive here on every keystroke.
    expect(contrastRatio("#ab", "#ffffff")).toBeNull();
    expect(contrastRatio("", "#ffffff")).toBeNull();
    expect(contrastRatio("rebeccapurple", "#ffffff")).toBeNull();
  });

  test("agrees with the backend on the thresholds it enforces", () => {
    // #bbbbbb on white is the validator's rejected-body-text fixture; #8f8f8f is the one it
    // accepts as a button fill but would reject as text.
    expect(contrastRatio("#bbbbbb", "#ffffff")!).toBeLessThan(AA_NORMAL_TEXT);
    const brand = contrastRatio("#8f8f8f", "#ffffff")!;
    expect(brand).toBeGreaterThanOrEqual(AA_NON_TEXT);
    expect(brand).toBeLessThan(AA_NORMAL_TEXT);
  });
});
