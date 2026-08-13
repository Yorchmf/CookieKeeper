import {
  Archive,
  BarChart3,
  FileText,
  Languages,
  Palette,
  ScanSearch,
  Server,
  ToggleRight,
  Zap,
} from "lucide-react";
import { useTranslations } from "next-intl";
import type { LucideIcon } from "lucide-react";
import { Reveal } from "@/components/marketing/reveal";
import { cn } from "@/lib/utils";

type Card = {
  key: string;
  icon: LucideIcon;
  /** Featured cards carry the brand accent to break uniform emphasis. */
  featured?: boolean;
};

/** Nine cards so the grid fills an even 3x3 at `lg:grid-cols-3` — no dangling last row. */
const CARDS: Card[] = [
  { key: "design", icon: Palette },
  { key: "scanner", icon: ScanSearch },
  { key: "consentMode", icon: ToggleRight, featured: true },
  { key: "policy", icon: FileText },
  { key: "languages", icon: Languages },
  { key: "hosting", icon: Server, featured: true },
  { key: "logs", icon: Archive },
  { key: "performance", icon: Zap },
  { key: "analytics", icon: BarChart3 },
];

export function ProblemSection() {
  const t = useTranslations("marketing.problem");

  return (
    <section
      id="features"
      aria-labelledby="features-heading"
      className="mx-auto max-w-6xl scroll-mt-20 px-6 py-24"
    >
      <Reveal className="mx-auto mb-14 max-w-2xl text-center">
        <p className="text-xs font-medium tracking-[0.2em] text-brand uppercase">
          {t("eyebrow")}
        </p>
        <h2
          id="features-heading"
          className="mt-3 font-heading text-3xl font-semibold tracking-tight text-balance sm:text-4xl"
        >
          {t("title")}
        </h2>
        <p className="mt-4 text-pretty text-muted-foreground">{t("subtitle")}</p>
      </Reveal>

      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {CARDS.map((card, index) => {
          const Icon = card.icon;
          return (
            <Reveal as="li" key={card.key} delay={index * 60}>
              <article
                className={cn(
                  "group flex h-full flex-col gap-3 rounded-xl border p-6 transition-all duration-300 hover:-translate-y-1",
                  card.featured
                    ? "border-brand/30 bg-brand-subtle/40 hover:border-brand/60"
                    : "border-border bg-card hover:border-brand/40 hover:shadow-lg hover:shadow-black/5",
                )}
              >
                <span
                  className={cn(
                    "inline-flex size-10 items-center justify-center rounded-lg transition-colors",
                    card.featured
                      ? "bg-brand text-brand-foreground"
                      : "bg-muted text-foreground group-hover:bg-brand-subtle group-hover:text-brand-subtle-foreground",
                  )}
                >
                  <Icon className="size-5" />
                </span>
                <h3 className="text-lg font-semibold tracking-tight">
                  {t(`cards.${card.key}.title`)}
                </h3>
                <p className="text-sm text-pretty text-muted-foreground">
                  {t(`cards.${card.key}.body`)}
                </p>
              </article>
            </Reveal>
          );
        })}
      </ul>
    </section>
  );
}
