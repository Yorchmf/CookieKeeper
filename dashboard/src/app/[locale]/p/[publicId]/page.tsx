import { setRequestLocale } from "next-intl/server";
import { HostedPolicy } from "@/components/policy/hosted-policy";

/**
 * Public hosted cookie policy. The backend hands customers a locale-less URL (`/p/{publicId}`); a
 * top-level rewrite in `next.config.ts` maps that onto this locale-scoped route so it still lands inside
 * the `[locale]` layout (which owns `<html>`/`<body>` and the i18n provider). Accessing an explicit
 * `/{locale}/p/{publicId}` works too. The policy content itself is localized by the backend via `?lang`.
 */
export default async function HostedPolicyPage(props: {
  params: Promise<{ locale: string; publicId: string }>;
}) {
  const { locale, publicId } = await props.params;
  setRequestLocale(locale);

  return <HostedPolicy publicId={publicId} />;
}
