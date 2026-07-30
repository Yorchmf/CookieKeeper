import { setRequestLocale } from "next-intl/server";
import { useTranslations } from "next-intl";
import { use } from "react";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { SitesCountCard } from "@/components/dashboard/sites-count-card";

const OVERVIEW_CARDS = [
  { titleKey: "cards.consents", hintKey: "cards.consentsHint" },
  { titleKey: "cards.scans", hintKey: "cards.scansHint" },
] as const;

export default function DashboardPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = use(props.params);
  setRequestLocale(locale);

  const t = useTranslations("dashboard");

  return (
    <main className="flex-1 p-6">
      <section aria-labelledby="dashboard-heading" className="flex flex-col gap-6">
        <header>
          <h1 id="dashboard-heading" className="text-2xl font-semibold tracking-tight">
            {t("title")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t("placeholder")}
          </p>
        </header>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <SitesCountCard />
          {OVERVIEW_CARDS.map((card) => (
            <Card key={card.titleKey}>
              <CardHeader>
                <CardTitle>{t(card.titleKey)}</CardTitle>
                <CardDescription>{t(card.hintKey)}</CardDescription>
              </CardHeader>
            </Card>
          ))}
        </div>
      </section>
    </main>
  );
}
