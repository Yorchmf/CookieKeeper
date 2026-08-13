"use client";

import { useTranslations } from "next-intl";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
} from "@/hooks/use-account";
import type { NotificationPreferences } from "@/lib/api/account";

/** The togglable keys, in display order. Each maps to a `settings.notifications.<key>` copy block. */
const TOGGLES = ["scanComplete", "scanChanges"] as const;

/**
 * Email notification preferences on `/settings/notifications`. Two switches gate the only two emails we
 * send off a scan: the first-scan summary when a site is added, and the change alert when a scheduled
 * re-scan finds new or changed trackers. Toggling is optimistic (see
 * {@link useUpdateNotificationPreferences}), so the switch moves at once and rolls back only if the save
 * fails — a save is a low-stakes preference, so there is no confirmation step and no re-auth.
 */
export function NotificationsCard() {
  const t = useTranslations("settings.notifications");
  const { data, isPending, isError } = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        {isError ? (
          <p className="text-sm text-destructive">{t("loadError")}</p>
        ) : (
          TOGGLES.map((key) => (
            <div
              key={key}
              className="flex items-start justify-between gap-4"
            >
              <div className="space-y-0.5">
                <label htmlFor={`notify-${key}`} className="text-sm font-medium">
                  {t(`${key}.label`)}
                </label>
                <p className="text-sm text-muted-foreground">
                  {t(`${key}.description`)}
                </p>
              </div>
              {isPending || !data ? (
                <Skeleton className="h-6 w-10 shrink-0 rounded-full" />
              ) : (
                <Switch
                  id={`notify-${key}`}
                  checked={data[key]}
                  onCheckedChange={(checked) =>
                    update.mutate(nextPreferences(data, key, checked))
                  }
                />
              )}
            </div>
          ))
        )}
      </CardContent>
    </Card>
  );
}

/** Full replace of the pair with one flag flipped — the PUT requires both, so never send a partial. */
function nextPreferences(
  current: NotificationPreferences,
  key: (typeof TOGGLES)[number],
  checked: boolean,
): NotificationPreferences {
  return { ...current, [key]: checked };
}
