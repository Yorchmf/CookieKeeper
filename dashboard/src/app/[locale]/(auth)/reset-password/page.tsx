import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";
import { ResetPasswordForm } from "@/components/auth/reset-password-form";

export default async function ResetPasswordPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    // Suspense boundary required by useSearchParams (?token=) during prerender.
    <Suspense>
      <ResetPasswordForm />
    </Suspense>
  );
}
