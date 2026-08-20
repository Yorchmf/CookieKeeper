import { describe, expect, test } from "vitest";
import {
  NO_EMBED_OPTIONS,
  withEmbedOptions,
  type EmbedOptions,
} from "@/lib/embed-options";

/** The snippet shape the backend generates, as `SiteService.embedSnippet()` writes it. */
const SNIPPET =
  '<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_abc"></script>';

/** Options with only the named ones on. */
function only(...on: (keyof EmbedOptions)[]): EmbedOptions {
  return {
    regionGating: on.includes("regionGating"),
    urlPassthrough: on.includes("urlPassthrough"),
  };
}

describe("withEmbedOptions", () => {
  test("returns the snippet untouched when nothing is selected", () => {
    expect(withEmbedOptions(SNIPPET, NO_EMBED_OPTIONS)).toBe(SNIPPET);
  });

  test("adds the region attribute with the only value the widget accepts", () => {
    // "gdpr" is not a free-text field: main.ts warns and ignores anything else,
    // so a typo here would silently turn the feature off.
    expect(withEmbedOptions(SNIPPET, only("regionGating"))).toBe(
      '<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_abc" data-complyr-regions="gdpr"></script>',
    );
  });

  test("adds url passthrough as a bare attribute", () => {
    expect(withEmbedOptions(SNIPPET, only("urlPassthrough"))).toBe(
      '<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_abc" data-complyr-url-passthrough></script>',
    );
  });

  test("adds both, still inside the opening tag", () => {
    const result = withEmbedOptions(
      SNIPPET,
      only("regionGating", "urlPassthrough"),
    );

    expect(result).toBe(
      '<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_abc" data-complyr-regions="gdpr" data-complyr-url-passthrough></script>',
    );
    // The site key must survive intact — it is what the attributes are added to.
    expect(result).toContain('data-complyr="pk_live_abc"');
  });

  test("leaves a snippet it does not recognise alone rather than mangling it", () => {
    const odd = "not a script tag at all";

    expect(withEmbedOptions(odd, only("regionGating"))).toBe(odd);
  });

  test("only ever touches the first tag close", () => {
    // Defensive: were the snippet ever to carry a second tag, the attributes
    // belong to our script, not to whatever follows it.
    const two = `${SNIPPET}<script></script>`;

    const result = withEmbedOptions(two, only("regionGating"));

    expect(result.endsWith("<script></script>")).toBe(true);
    expect(result.match(/data-complyr-regions/g)).toHaveLength(1);
  });
});
