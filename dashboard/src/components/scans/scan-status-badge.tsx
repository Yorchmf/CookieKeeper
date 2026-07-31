"use client";

import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import type { ScanStatus } from "@/lib/api/scans";

/** Maps a scan lifecycle state to a badge tone: done is positive, failed is destructive. */
const STATUS_VARIANT: Record<
  ScanStatus,
  "default" | "secondary" | "outline" | "destructive"
> = {
  done: "default",
  running: "secondary",
  queued: "outline",
  failed: "destructive",
};

export function ScanStatusBadge({ status }: { status: ScanStatus }) {
  const t = useTranslations("scans");
  // Fall back gracefully if the backend ever adds a status the UI doesn't map yet,
  // rather than crashing on a missing translation key.
  const variant = STATUS_VARIANT[status] ?? "outline";
  const label = t.has(`status.${status}`) ? t(`status.${status}`) : status;
  return <Badge variant={variant}>{label}</Badge>;
}
