import { setRequestLocale } from "next-intl/server";
import { use } from "react";

import { DashboardHome } from "@/components/dashboard/dashboard-home";

export default function DashboardPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = use(props.params);
  setRequestLocale(locale);

  return <DashboardHome />;
}
