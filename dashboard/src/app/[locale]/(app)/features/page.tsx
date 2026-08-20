import { setRequestLocale } from "next-intl/server";

import { FeatureIndex } from "@/components/features/feature-index";

/**
 * The in-app feature index. No server data of its own: the view reads the account's sites (to deep-link
 * per-site capabilities) and its entitlement (to render the plan-gated ones as locked) from the shared
 * client queries, so the page is a thin locale shell.
 */
export default async function FeaturesPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return <FeatureIndex />;
}
