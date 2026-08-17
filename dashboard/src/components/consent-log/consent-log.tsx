"use client";

import { useTranslations } from "next-intl";
import { type ReadonlyURLSearchParams, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef } from "react";

import { ConsentEventsTable } from "@/components/consent-log/consent-events-table";
import { ConsentLogFilters as FiltersBar } from "@/components/consent-log/consent-log-filters";
import { ExportCsvButton } from "@/components/consent-log/export-csv-button";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useConsentLog } from "@/hooks/use-consent-log";
import { usePathname, useRouter } from "@/i18n/navigation";
import { cn } from "@/lib/utils";
import {
  buildConsentParams,
  type ConsentEvent,
  type ConsentLogFilters,
  hasActiveConsentFilters,
  parseConsentAction,
} from "@/lib/api/consent";

/** Read the supported filter fields out of the URL (undefined for anything absent). */
function readFilters(params: ReadonlyURLSearchParams): ConsentLogFilters {
  const get = (key: string) => params.get(key) || undefined;
  return {
    from: get("from"),
    to: get("to"),
    // The URL is user-controlled: validate `action` against the known set rather than trusting the cast.
    action: parseConsentAction(params.get("action")),
    lang: get("lang"),
    visitorId: get("visitorId"),
  };
}

/** Loading placeholder while the first keyset page is in flight. Exported for the route Suspense fallback. */
export function ConsentLogSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn("flex flex-col gap-3", className)} aria-hidden="true">
      {Array.from({ length: 6 }).map((_, index) => (
        <Skeleton key={index} className="h-10 w-full" />
      ))}
    </div>
  );
}

type ConsentLogQuery = ReturnType<typeof useConsentLog>;

/** Exported for unit tests: the render-state machine (loading / error / two empty states / list). */
export function ConsentLogBody({
  query,
  events,
  isFiltered,
  onClearFilters,
  sentinelRef,
}: {
  query: ConsentLogQuery;
  events: ConsentEvent[];
  isFiltered: boolean;
  onClearFilters: () => void;
  sentinelRef: React.RefObject<HTMLDivElement | null>;
}) {
  const t = useTranslations("consentLog");

  if (query.isPending) return <ConsentLogSkeleton />;
  if (query.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {t("loadError")}
      </p>
    );
  }
  if (events.length === 0) {
    // Two very different empty states: an active filter set that matched nothing (the fix is to clear
    // the filters, offered inline) versus a site that has genuinely recorded no consent yet (the fix is
    // out in the world — get the banner in front of visitors — so the copy just explains that).
    if (isFiltered) {
      return (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border p-10 text-center">
          <p className="font-medium">{t("empty.filtered.title")}</p>
          <p className="max-w-md text-sm text-muted-foreground">
            {t("empty.filtered.description")}
          </p>
          <Button type="button" variant="secondary" onClick={onClearFilters}>
            {t("filters.clear")}
          </Button>
        </div>
      );
    }
    return (
      <div className="rounded-xl border border-dashed border-border p-10 text-center">
        <p className="font-medium">{t("empty.title")}</p>
        <p className="mt-1 text-sm text-muted-foreground">{t("empty.description")}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <ConsentEventsTable events={events} caption={t("tableCaption")} />
      <div ref={sentinelRef} aria-hidden="true" className="h-px" />
      {query.isFetchingNextPage && (
        <p role="status" className="text-center text-sm text-muted-foreground">
          {t("loadingMore")}
        </p>
      )}
      {!query.hasNextPage && (
        <p className="text-center text-xs text-muted-foreground">{t("endOfLog")}</p>
      )}
    </div>
  );
}

/**
 * Consent audit-log browser for one site. Filters live in the URL (shareable, back-button friendly);
 * changing one starts a fresh keyset query. An IntersectionObserver sentinel loads the next (older)
 * page as it scrolls into view.
 */
export function ConsentLog({ siteId }: { siteId: string }) {
  const t = useTranslations("consentLog");
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();

  const filters = useMemo(() => readFilters(searchParams), [searchParams]);
  const query = useConsentLog(siteId, filters);
  const events = useMemo(
    () => query.data?.pages.flatMap((page) => page.events) ?? [],
    [query.data],
  );

  const applyFilters = useCallback(
    (next: ConsentLogFilters) => {
      const queryString = buildConsentParams(next).toString();
      router.replace(queryString ? `${pathname}?${queryString}` : pathname, { scroll: false });
    },
    [pathname, router],
  );

  const sentinelRef = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query;
  useEffect(() => {
    const element = sentinelRef.current;
    if (!element || !hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !isFetchingNextPage) {
          void fetchNextPage();
        }
      },
      { rootMargin: "240px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  return (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="consent-log-heading"
        className="flex max-w-5xl flex-col gap-6"
      >
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-1">
            <h1 id="consent-log-heading" className="text-2xl font-semibold tracking-tight">
              {t("title")}
            </h1>
            <p className="max-w-2xl text-sm text-muted-foreground">{t("subtitle")}</p>
          </div>
          <ExportCsvButton siteId={siteId} filters={filters} />
        </header>

        <FiltersBar values={filters} onChange={applyFilters} />

        <ConsentLogBody
          query={query}
          events={events}
          isFiltered={hasActiveConsentFilters(filters)}
          onClearFilters={() => applyFilters({})}
          sentinelRef={sentinelRef}
        />
      </section>
    </main>
  );
}
