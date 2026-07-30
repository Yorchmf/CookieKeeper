import { setRequestLocale } from "next-intl/server";
import { SitesList } from "@/components/sites/sites-list";

export default async function SitesPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return <SitesList />;
}
