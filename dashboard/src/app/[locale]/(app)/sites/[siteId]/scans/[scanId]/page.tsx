import { setRequestLocale } from "next-intl/server";
import { ScanResults } from "@/components/scans/scan-results";

export default async function ScanResultsPage(props: {
  params: Promise<{ locale: string; siteId: string; scanId: string }>;
}) {
  const { locale, siteId, scanId } = await props.params;
  setRequestLocale(locale);

  return <ScanResults siteId={siteId} scanId={scanId} />;
}
