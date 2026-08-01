import { setRequestLocale } from "next-intl/server";
import { PolicyManager } from "@/components/policy/policy-manager";

export default async function PolicyPage(props: {
  params: Promise<{ locale: string; siteId: string }>;
}) {
  const { locale, siteId } = await props.params;
  setRequestLocale(locale);

  return <PolicyManager siteId={siteId} />;
}
