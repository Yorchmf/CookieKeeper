import { redirect } from "@/i18n/navigation";

/** `/settings` has no content of its own — send it to the first surface rather than 404. */
export default async function SettingsIndexPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  redirect({ href: "/settings/data", locale });
}
