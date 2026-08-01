import { setRequestLocale } from "next-intl/server";
import { BannerManager } from "@/components/banner/banner-manager";

export default async function BannerPage(props: {
  params: Promise<{ locale: string; siteId: string }>;
}) {
  const { locale, siteId } = await props.params;
  setRequestLocale(locale);

  return <BannerManager siteId={siteId} />;
}
