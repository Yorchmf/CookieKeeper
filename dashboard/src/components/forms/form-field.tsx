"use client";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type FormFieldProps = {
  id: string;
  label: string;
  error?: string;
} & React.ComponentProps<"input">;

/**
 * Labelled input with accessible error wiring: label/input association via
 * `htmlFor`, `aria-invalid` on error, and error text linked through
 * `aria-describedby`.
 */
export function FormField({ id, label, error, ...inputProps }: FormFieldProps) {
  const errorId = `${id}-error`;

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        {...inputProps}
      />
      {error ? (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
