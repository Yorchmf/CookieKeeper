"use client"

import { Switch as SwitchPrimitive } from "@base-ui/react/switch"

import { cn } from "@/lib/utils"

/**
 * On/off toggle over the Base UI `Switch`. Renders a `<span>` track plus a hidden `<input>`, so it is
 * form- and label-associable through `id`/`name` like a native checkbox. The visual state is driven by
 * Base UI's `data-checked` / `data-unchecked` attributes rather than a `checked` class, which keeps the
 * styling correct for both controlled and uncontrolled use.
 */
function Switch({ className, ...props }: SwitchPrimitive.Root.Props) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        "peer inline-flex h-6 w-10 shrink-0 items-center rounded-full p-0.5 outline-none transition-colors",
        "ring-1 ring-inset ring-foreground/15 data-checked:bg-primary data-unchecked:bg-input",
        "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
        "disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className={cn(
          "pointer-events-none block size-5 rounded-full bg-background shadow-sm ring-1 ring-foreground/10 transition-transform",
          "data-checked:translate-x-4 data-unchecked:translate-x-0"
        )}
      />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
