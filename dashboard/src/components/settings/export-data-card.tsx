"use client";

import { DownloadIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ACCOUNT_EXPORT_PATH } from "@/lib/api/account";

/** The parts of the document, listed so the customer knows what they are getting before downloading. */
const EXPORT_CONTENTS = [
  "account",
  "subscription",
  "sites",
  "banner",
  "policy",
  "scans",
] as const;

/**
 * Art. 20 data portability. The download is a plain same-origin `<a download>` — the backend streams
 * the JSON with a `Content-Disposition` attachment header and the auth cookies attach automatically,
 * so no fetch, no blob, and nothing about the document ever lands in client memory.
 */
export function ExportDataCard() {
  const t = useTranslations("settings.data.export");

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">{t("includesTitle")}</h3>
          <ul className="list-disc pl-5 text-sm text-muted-foreground">
            {EXPORT_CONTENTS.map((item) => (
              <li key={item}>{t(`includes.${item}`)}</li>
            ))}
          </ul>
        </div>
        <p className="text-sm text-muted-foreground">{t("consentNote")}</p>
        <div>
          <Button
            nativeButton={false}
            render={<a href={ACCOUNT_EXPORT_PATH} download />}
          >
            <DownloadIcon aria-hidden="true" />
            {t("download")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
