import { setRequestLocale } from "next-intl/server";
import { ForgotPasswordForm } from "@/components/auth/forgot-password-form";

export default async function ForgotPasswordPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return <ForgotPasswordForm />;
}
