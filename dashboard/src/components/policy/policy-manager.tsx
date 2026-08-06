"use client";

import { useFormatter, useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { CopyButton } from "@/components/policy/copy-button";
import { LanguageSwitcher } from "@/components/policy/language-switcher";
import { PolicyForm } from "@/components/policy/policy-form";
import { PolicyHtml } from "@/components/policy/policy-html";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  usePolicy,
  usePolicyPreview,
  useGeneratePolicy,
} from "@/hooks/use-policy";
import { useSite } from "@/hooks/use-sites";
import { Link } from "@/i18n/navigation";
import type { PolicyCurrent, PolicyGenerationInput } from "@/lib/api/policy";

/** Dashboard policy surface: business-details form, published summary, and a language-tabbed preview. */
export function PolicyManager({ siteId }: { siteId: string }) {
  const t = useTranslations("policy");
  const policy = usePolicy(siteId);
  // Only for the hosted-link notice: the public page 404s until the domain is verified (ADR-17), so
  // the customer must not be handed a link that looks live and isn't. Nothing else here depends on it.
  const site = useSite(siteId);
  const generate = useGeneratePolicy(siteId);
  const [selectedLang, setSelectedLang] = useState<string | undefined>(undefined);

  const current = policy.data ?? null;
  // Keep the preview language valid across regenerations: fall back to the first available language
  // whenever the current selection isn't offered by the published version.
  const effectiveLang =
    current && selectedLang && current.languages.includes(selectedLang)
      ? selectedLang
      : (current?.languages[0] ?? undefined);

  const handleGenerate = async (input: PolicyGenerationInput) => {
    try {
      await generate.mutateAsync(input);
      toast.success(t("form.generated"));
    } catch {
      toast.error(t("form.error"));
    }
  };

  if (policy.isPending) {
    return (
      <main className="flex-1 p-6" aria-busy="true">
        <div className="flex max-w-3xl flex-col gap-4">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </main>
    );
  }

  if (policy.isError) {
    return (
      <main className="flex-1 p-6">
        <p role="alert" className="text-sm text-destructive">
          {t("loadError")}
        </p>
      </main>
    );
  }

  return (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="policy-heading"
        className="flex max-w-3xl flex-col gap-6"
      >
        <header className="flex flex-col gap-1">
          <h1 id="policy-heading" className="text-2xl font-semibold tracking-tight">
            {t("title")}
          </h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>{t("form.title")}</CardTitle>
            <CardDescription>{t("form.description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <PolicyForm
              hasExisting={current !== null}
              isSubmitting={generate.isPending}
              onSubmit={(input) => void handleGenerate(input)}
            />
          </CardContent>
        </Card>

        {current ? (
          <>
            <PublishedCard
              current={current}
              selectedLang={effectiveLang}
              unverifiedDomain={
                site.data && site.data.verifiedAt === null
                  ? site.data.domain
                  : null
              }
              siteId={siteId}
            />
            <PreviewCard
              siteId={siteId}
              lang={effectiveLang}
              onSelectLang={setSelectedLang}
            />
          </>
        ) : (
          <Card>
            <CardHeader>
              <CardTitle>{t("empty.title")}</CardTitle>
              <CardDescription>{t("empty.description")}</CardDescription>
            </CardHeader>
          </Card>
        )}
      </section>
    </main>
  );
}

/**
 * Version, publish date, languages, the hosted URL, and the copyable embed block.
 *
 * `unverifiedDomain` is the domain when the site is *not* yet verified, and null otherwise — the
 * hosted link below is dead until then, so the notice says so rather than letting the customer paste
 * a 404 into their footer.
 */
function PublishedCard({
  current,
  selectedLang,
  unverifiedDomain,
  siteId,
}: {
  current: PolicyCurrent;
  selectedLang: string | undefined;
  unverifiedDomain: string | null;
  siteId: string;
}) {
  const t = useTranslations("policy.published");
  const tNotice = useTranslations("policy.unverifiedNotice");
  const format = useFormatter();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>
          {t("version", { version: current.version })}
          {current.publishedAt
            ? ` · ${t("updated", { date: format.dateTime(new Date(current.publishedAt), { dateStyle: "medium" }) })}`
            : ""}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">{t("languages")}</span>
          <div className="flex flex-wrap gap-2">
            {current.languages.map((lang) => (
              <Badge
                key={lang}
                variant={lang === selectedLang ? "default" : "outline"}
              >
                {lang.toUpperCase()}
              </Badge>
            ))}
          </div>
        </div>

        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">{t("hostedUrl")}</span>
          {unverifiedDomain ? (
            <div
              id="policy-unverified-notice"
              className="flex flex-col gap-1 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-900 dark:bg-amber-950/40"
            >
              <span className="font-medium">{tNotice("title")}</span>
              <span className="text-muted-foreground">
                {tNotice("description", { domain: unverifiedDomain })}
              </span>
              <Link
                href={`/sites/${siteId}`}
                className="w-fit font-medium underline underline-offset-4"
              >
                {tNotice("cta")}
              </Link>
            </div>
          ) : null}
          <div className="flex flex-wrap items-center gap-2">
            <a
              href={current.hostedUrl}
              target="_blank"
              rel="noopener noreferrer"
              aria-describedby={
                unverifiedDomain ? "policy-unverified-notice" : undefined
              }
              className="truncate rounded bg-muted px-2 py-1 font-mono text-xs underline-offset-4 hover:underline"
            >
              {current.hostedUrl}
            </a>
            <Button
              variant="outline"
              size="sm"
              render={
                <a
                  href={current.hostedUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                />
              }
            >
              {t("open")}
            </Button>
            <CopyButton
              value={current.hostedUrl}
              label={t("copyUrl")}
              copiedLabel={t("copied")}
            />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Language-tabbed live preview of the hosted policy, plus the copyable HTML embed block.
 *
 * Reached by site id through the authenticated preview endpoint, not by public id through the hosted
 * read: the hosted page is gated on domain verification (ADR-17) and the owner has to see the page
 * before they can prove they control the domain.
 */
function PreviewCard({
  siteId,
  lang,
  onSelectLang,
}: {
  siteId: string;
  lang: string | undefined;
  onSelectLang: (lang: string) => void;
}) {
  const t = useTranslations("policy");
  const preview = usePolicyPreview(siteId, lang);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("preview.title")}</CardTitle>
        <CardDescription>{t("preview.description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {preview.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : preview.isError || !preview.data ? (
          <p role="alert" className="text-sm text-destructive">
            {t("preview.loadError")}
          </p>
        ) : (
          <>
            <LanguageSwitcher
              label={t("preview.languageLabel")}
              languages={preview.data.availableLanguages}
              current={preview.data.language}
              onSelect={onSelectLang}
            />
            <div
              lang={preview.data.language}
              className="max-h-96 overflow-auto rounded-lg border border-border bg-background p-6"
            >
              <PolicyHtml html={preview.data.html} />
            </div>
            <div className="flex flex-col gap-2">
              <span className="text-sm font-medium">
                {t("published.embedTitle")}
              </span>
              <p className="text-sm text-muted-foreground">
                {t("published.embedDescription")}
              </p>
              <pre className="max-h-48 overflow-auto rounded-lg border border-border bg-muted/50 p-4 text-xs leading-relaxed">
                <code>{preview.data.html}</code>
              </pre>
              <div>
                <CopyButton
                  value={preview.data.html}
                  label={t("published.copyHtml")}
                  copiedLabel={t("published.copied")}
                />
              </div>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
