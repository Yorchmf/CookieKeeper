import { setRequestLocale } from "next-intl/server";
import { SignupForm } from "@/components/auth/signup-form";

export default async function SignupPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return <SignupForm />;
}
