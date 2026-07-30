import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";

export default function AuthLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const t = useTranslations("app");

  return (
    <main className="flex min-h-dvh flex-1 flex-col items-center justify-center gap-6 bg-muted/40 p-4">
      <Link href="/" className="text-lg font-semibold tracking-tight">
        {t("name")}
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </main>
  );
}
