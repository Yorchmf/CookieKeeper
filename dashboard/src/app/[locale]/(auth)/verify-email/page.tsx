import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";
import { VerifyEmailPanel } from "@/components/auth/verify-email-panel";

export default async function VerifyEmailPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    // Suspense boundary required by useSearchParams (?token=) during prerender.
    <Suspense>
      <VerifyEmailPanel />
    </Suspense>
  );
}
