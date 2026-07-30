"use client";

import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { useLogout, useMe } from "@/hooks/use-auth";
import { useRouter } from "@/i18n/navigation";

export function UserNav() {
  const t = useTranslations();
  const router = useRouter();
  const me = useMe();
  const logout = useLogout();

  const handleSignOut = async () => {
    try {
      await logout.mutateAsync();
    } catch {
      // The session cookie is still set, so the proxy would bounce us right
      // back from /login — stay put and let the user retry instead.
      toast.error(t("nav.signOutError"));
      return;
    }
    router.replace("/login");
  };

  if (me.isPending) {
    return <Skeleton className="h-8 w-40" />;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button variant="ghost" size="sm">
            {me.data?.email ?? t("nav.account")}
          </Button>
        }
      />
      <DropdownMenuContent align="end">
        {me.data ? (
          <>
            <DropdownMenuLabel>{me.data.email}</DropdownMenuLabel>
            <DropdownMenuSeparator />
          </>
        ) : null}
        <DropdownMenuItem
          onClick={() => void handleSignOut()}
          disabled={logout.isPending}
        >
          {t("nav.signOut")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
