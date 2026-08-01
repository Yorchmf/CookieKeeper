/**
 * Renders the backend-produced cookie-policy HTML block.
 *
 * SECURITY — `dangerouslySetInnerHTML`: `html` is NOT untrusted input handed to the browser raw. It is
 * generated server-side by `PolicyRenderer` (backend `PolicyRenderer.kt`), which routes every dynamic
 * value — company name, contact email, and attacker-influenced scanned cookie names/providers — through
 * `HtmlEscape`, and draws all surrounding markup and wording from the trusted `PolicyStrings` bundle.
 * The block is, by design, the exact self-contained markup a customer pastes into their own site. We
 * inject it verbatim here; the escaping guarantee lives at the source, not at this call site. The API is
 * same-origin and JWT/opaque-id gated, so the response cannot be attacker-substituted in transit.
 *
 * Styling is scoped to this wrapper via descendant selectors so the policy's plain semantic HTML
 * (`h1`/`h2`/`p`/`table`) picks up the dashboard's design tokens without a global stylesheet.
 */
export function PolicyHtml({ html }: { html: string }) {
  return (
    <div
      className={[
        "text-sm leading-relaxed text-foreground",
        "[&_h1]:text-2xl [&_h1]:font-semibold [&_h1]:tracking-tight",
        "[&_h2]:mt-8 [&_h2]:text-lg [&_h2]:font-semibold [&_h2]:tracking-tight",
        "[&_p]:mt-3",
        "[&_.cmplyr-policy-updated]:text-xs [&_.cmplyr-policy-updated]:text-muted-foreground",
        "[&_table]:mt-4 [&_table]:w-full [&_table]:border-collapse [&_table]:text-left",
        "[&_th]:border [&_th]:border-border [&_th]:bg-muted [&_th]:px-3 [&_th]:py-2 [&_th]:font-medium",
        "[&_td]:border [&_td]:border-border [&_td]:px-3 [&_td]:py-2 [&_td]:align-top",
      ].join(" ")}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
