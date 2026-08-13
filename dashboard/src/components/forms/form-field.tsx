"use client";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type FormFieldProps = {
  id: string;
  label: string;
  error?: string;
  /** Optional persistent helper text (e.g. a consequence of saving), linked via `aria-describedby`. */
  hint?: string;
} & React.ComponentProps<"input">;

/**
 * Labelled input with accessible error wiring: label/input association via
 * `htmlFor`, `aria-invalid` on error, and error text plus any hint linked
 * through `aria-describedby` so screen-reader users hear them on focus.
 */
export function FormField({
  id,
  label,
  error,
  hint,
  ...inputProps
}: FormFieldProps) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(" ") ||
    undefined;

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...inputProps}
      />
      {hint ? (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
