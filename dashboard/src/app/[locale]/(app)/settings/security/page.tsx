import { setRequestLocale } from "next-intl/server";
import { SecurityCard } from "@/components/settings/security-card";

export default async function SettingsSecurityPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    <div className="flex flex-col gap-6">
      <SecurityCard />
    </div>
  );
}
