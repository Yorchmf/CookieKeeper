import { setRequestLocale } from "next-intl/server";
import { NotificationsCard } from "@/components/settings/notifications-card";

export default async function SettingsNotificationsPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    <div className="flex flex-col gap-6">
      <NotificationsCard />
    </div>
  );
}
