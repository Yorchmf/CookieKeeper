"use client";

import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

type FormTextareaProps = {
  id: string;
  label: string;
  error?: string;
  /** Optional persistent helper text (e.g. a character budget), linked via `aria-describedby`. */
  hint?: string;
} & React.ComponentProps<"textarea">;

/**
 * Labelled textarea with the same accessible error wiring as {@link FormField}:
 * label/textarea association via `htmlFor`, `aria-invalid` on error, and error
 * text plus any hint linked through `aria-describedby` so screen-reader users
 * hear them on focus.
 */
export function FormTextarea({
  id,
  label,
  error,
  hint,
  ...textareaProps
}: FormTextareaProps) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(" ") ||
    undefined;

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...textareaProps}
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
