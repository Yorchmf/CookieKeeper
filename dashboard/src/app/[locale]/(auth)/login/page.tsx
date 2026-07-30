import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";

export default async function LoginPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    // Suspense boundary required by useSearchParams (?next=) during prerender.
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
