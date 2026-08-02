import { setRequestLocale } from "next-intl/server";
import { BillingManager } from "@/components/billing/billing-manager";

export default async function BillingPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return <BillingManager />;
}
