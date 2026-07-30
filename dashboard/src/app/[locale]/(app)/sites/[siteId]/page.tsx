import { setRequestLocale } from "next-intl/server";
import { SiteDetail } from "@/components/sites/site-detail";

export default async function SiteDetailPage(props: {
  params: Promise<{ locale: string; siteId: string }>;
}) {
  const { locale, siteId } = await props.params;
  setRequestLocale(locale);

  return <SiteDetail siteId={siteId} />;
}
