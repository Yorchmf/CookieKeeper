/**
 * The two optional widget behaviours a site owner turns on by adding an attribute to their embed
 * snippet. They live on the `<script>` tag rather than in the per-site banner config because the
 * widget must act on both before it has fetched anything: Consent Mode defaults are pushed
 * synchronously at the top of the bundle, and a config round trip would arrive far too late.
 *
 * Which means the dashboard's job here is only to write the right snippet — there is nothing to
 * save and nothing that can drift out of sync with the server.
 */
export type EmbedOptions = {
  /**
   * `data-complyr-regions="gdpr"` — deny by default and show the banner only in the EU/EEA, the UK
   * and Switzerland; grant by default and show nothing elsewhere.
   */
  regionGating: boolean;
  /**
   * `data-complyr-url-passthrough` — carry an ad-click id (`gclid`, `gbraid`, …) across the site's
   * own internal links while ad storage is denied, so a conversion stays attributable.
   */
  urlPassthrough: boolean;
};

/** Neither option on — the snippet exactly as the backend generated it. */
export const NO_EMBED_OPTIONS: EmbedOptions = {
  regionGating: false,
  urlPassthrough: false,
};

/**
 * Where the attributes go: immediately before the tag closes, after every attribute the backend
 * emitted. Matching on the closing sequence rather than parsing the snippet keeps this a pure
 * string function with no DOM dependency, and makes an unrecognised snippet a no-op instead of a
 * corrupted paste.
 */
const SCRIPT_TAG_CLOSE = "></script>";

/**
 * The embed snippet with the selected options' attributes added. Returns [snippet] untouched when
 * nothing is selected, and — deliberately — also when it does not look like the snippet we
 * generate: a customer copying a subtly mangled tag is far worse than one copying a tag without
 * the optional attribute they ticked.
 */
export function withEmbedOptions(
  snippet: string,
  options: EmbedOptions,
): string {
  const attributes =
    (options.regionGating ? ' data-complyr-regions="gdpr"' : "") +
    (options.urlPassthrough ? " data-complyr-url-passthrough" : "");
  if (attributes === "") return snippet;

  const closesAt = snippet.indexOf(SCRIPT_TAG_CLOSE);
  if (closesAt === -1) return snippet;
  return snippet.slice(0, closesAt) + attributes + snippet.slice(closesAt);
}
