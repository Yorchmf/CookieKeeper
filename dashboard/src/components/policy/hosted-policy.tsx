"use client";

import { useTranslations } from "next-intl";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { LanguageSwitcher } from "@/components/policy/language-switcher";
import { PolicyHtml } from "@/components/policy/policy-html";
import { usePublicPolicy } from "@/hooks/use-policy";
import { ApiError } from "@/lib/api";

/**
 * Standalone, public hosted cookie policy at `/p/{publicId}` — the URL the backend hands customers to
 * link. The visitor-facing content (title, wording) is fully localized by the backend per `?lang`; this
 * page's own chrome is minimal. Language selection is URL state (`?lang=`) so a link is shareable in a
 * specific language, and `router.replace` updates it without a full navigation.
 */
export function HostedPolicy({ publicId }: { publicId: string }) {
  const t = useTranslations("policy.hosted");
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const langParam = searchParams.get("lang") ?? undefined;
  const policy = usePublicPolicy(publicId, langParam);

  const handleSelectLang = (lang: string) => {
    const params = new URLSearchParams(searchParams);
    params.set("lang", lang);
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  const isNotFound =
    policy.error instanceof ApiError && policy.error.status === 404;

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-3xl flex-col gap-8 px-6 py-12">
      {policy.isPending ? (
        <p className="text-sm text-muted-foreground" role="status">
          {t("loading")}
        </p>
      ) : isNotFound ? (
        <PolicyMessage title={t("notFound.title")} description={t("notFound.description")} />
      ) : policy.isError || !policy.data ? (
        <PolicyMessage title={t("error.title")} description={t("error.description")} />
      ) : (
        <>
          <LanguageSwitcher
            label={t("languageLabel")}
            languages={policy.data.availableLanguages}
            current={policy.data.language}
            onSelect={handleSelectLang}
          />
          <article lang={policy.data.language}>
            <PolicyHtml html={policy.data.html} />
          </article>
        </>
      )}
      <footer className="mt-auto border-t border-border pt-6 text-xs text-muted-foreground">
        {t("poweredBy", { app: "Complyr" })}
      </footer>
    </main>
  );
}

function PolicyMessage({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div role="alert" className="flex flex-col gap-2">
      <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
      <p className="text-sm text-muted-foreground">{description}</p>
    </div>
  );
}
