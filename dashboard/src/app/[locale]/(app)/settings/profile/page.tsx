import { setRequestLocale } from "next-intl/server";
import { EmailCard } from "@/components/settings/email-card";
import { PasswordCard } from "@/components/settings/password-card";
import { ProfileNameCard } from "@/components/settings/profile-name-card";

export default async function SettingsProfilePage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    <div className="flex flex-col gap-6">
      <ProfileNameCard />
      <EmailCard />
      <PasswordCard />
    </div>
  );
}
