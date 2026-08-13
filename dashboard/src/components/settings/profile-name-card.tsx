"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useMemo } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { FormField } from "@/components/forms/form-field";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useUpdateName } from "@/hooks/use-account";
import { useMe } from "@/hooks/use-auth";
import { getApiErrorCode } from "@/lib/api-error-codes";

/** Matches the backend `@Size(max = 120)` on `UpdateProfileRequest.name`. */
const NAME_MAX = 120;

/**
 * The display-name half of `/settings/profile`. The name is optional: an empty field is a valid state
 * that clears it back to null, so there is no "required" rule — only the length bound, which mirrors
 * the backend. The current name seeds the field from the `me` cache via `values`; `keepDirtyValues` stops
 * a background `me` refresh (reconnect, cross-surface invalidation) from clobbering an in-progress edit,
 * and a successful save re-syncs the field explicitly from the authoritative PATCH response.
 */
export function ProfileNameCard() {
  const t = useTranslations("settings.profile.name");
  const tErrors = useTranslations("auth.errors");
  const { data: me } = useMe();
  const updateName = useUpdateName();

  const schema = useMemo(
    () => z.object({ name: z.string().max(NAME_MAX, t("tooLong")) }),
    [t],
  );

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    values: { name: me?.name ?? "" },
    resetOptions: { keepDirtyValues: true },
  });

  // Derive UI state from the mutation rather than mirroring it into local state. Editing again resets the
  // mutation, which clears both the "Saved" confirmation and any prior error.
  const errorCode = updateName.error ? getApiErrorCode(updateName.error) : null;
  const isSaved = updateName.isSuccess;

  const onSubmit = handleSubmit(async (values) => {
    try {
      const user = await updateName.mutateAsync(values.name);
      // Clear the dirty flag (disables Save) and re-seed from the saved value.
      reset({ name: user.name ?? "" });
    } catch {
      // Failure is surfaced through updateName.error → errorCode; nothing more to do here.
    }
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <FormError message={errorCode ? tErrors(errorCode) : null} />
          <FormField
            id="profile-name"
            label={t("label")}
            autoComplete="name"
            placeholder={t("placeholder")}
            error={errors.name?.message}
            {...register("name", {
              onChange: () => {
                if (updateName.isSuccess || updateName.isError) updateName.reset();
              },
            })}
          />
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={updateName.isPending || !isDirty}>
              {t("save")}
            </Button>
            {/* Live region is always mounted so screen readers announce the text change, not the node. */}
            <p role="status" className="text-sm text-muted-foreground">
              {isSaved ? t("saved") : ""}
            </p>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
