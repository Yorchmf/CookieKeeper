import { setRequestLocale } from "next-intl/server";
import { DeleteAccountCard } from "@/components/settings/delete-account-card";
import { ExportDataCard } from "@/components/settings/export-data-card";

export default async function SettingsDataPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    <div className="flex flex-col gap-6">
      <ExportDataCard />
      <DeleteAccountCard />
    </div>
  );
}
