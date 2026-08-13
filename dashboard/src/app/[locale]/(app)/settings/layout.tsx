import { useTranslations } from "next-intl";
import { SettingsNav } from "@/components/settings/settings-nav";

/**
 * Shell shared by every account settings surface: page heading plus the sub-nav. Each settings page
 * renders its own `<section>`s into `children` — the `<main>` landmark lives here so there is exactly
 * one per document.
 */
export default function SettingsLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const t = useTranslations("settings");

  return (
    <main className="flex-1 p-6">
      <div className="flex max-w-3xl flex-col gap-6">
        <header className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight">
            {t("title")}
          </h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </header>
        <SettingsNav />
        {children}
      </div>
    </main>
  );
}
