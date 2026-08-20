import type { ComponentProps } from "react";
import type { VariantProps } from "class-variance-authority";

import { buttonVariants } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

type ButtonLinkProps = ComponentProps<typeof Link> & VariantProps<typeof buttonVariants>;

/**
 * A navigation link that *looks* like a button and stays a link to assistive technology.
 *
 * Use this instead of `<Button render={<Link/>}>`. Base UI's button always stamps one of two
 * attributes onto whatever element `render` produces (`useButton`: `nativeButton ? {type:'button'} :
 * {role:'button'}`), and on an anchor both are wrong:
 *  - `nativeButton` left at its default puts `type="button"` on the anchor, where `type` means the MIME
 *    hint of the linked resource, and logs a dev-time error.
 *  - `nativeButton={false}` puts `role="button"` on it, which overrides the implicit link role: screen
 *    readers announce "button" for something that navigates, and the control disappears from the links
 *    list (NVDA Insert+F7, VoiceOver rotor) that many users navigate a page by. That is the
 *    name/role/value mismatch in WCAG 4.1.2, and it hit our primary marketing CTAs.
 *
 * Styling comes from the same `buttonVariants` the real button uses, so the two are pixel-identical.
 * For a plain `<a>` — an in-page `#hash` or a `download` that must not be locale-prefixed — apply
 * `buttonVariants()` directly rather than reaching for this.
 */
export function ButtonLink({ className, variant = "default", size = "default", ...props }: ButtonLinkProps) {
  return <Link data-slot="button" className={buttonVariants({ variant, size, className })} {...props} />;
}
