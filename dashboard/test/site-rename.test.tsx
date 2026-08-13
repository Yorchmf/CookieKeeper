import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { RenameSiteCard } from "@/components/sites/rename-site-card";
import { ApiError } from "@/lib/api";
import en from "../messages/en.json";

// Mock the API client, not the hook: the real `useUpdateSite` (and TanStack Query) then drive the
// mutation state the card derives its UI from — isPending/isError/reset all behave for real.
const updateSite = vi.fn();
vi.mock("@/lib/api/sites", () => ({
  updateSite: (id: string, input: { domain?: string }) => updateSite(id, input),
}));

// Assert the success toast without mounting a <Toaster>.
const toastSuccess = vi.fn();
vi.mock("sonner", () => ({ toast: { success: (...a: unknown[]) => toastSuccess(...a) } }));

function renderCard(props?: { domain?: string; isVerified?: boolean }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={en}>
        <RenameSiteCard
          siteId="site-1"
          domain={props?.domain ?? "old.example.eu"}
          isVerified={props?.isVerified ?? false}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function domainField(): HTMLInputElement {
  return screen.getByLabelText("Domain") as HTMLInputElement;
}

function saveButton(): HTMLButtonElement {
  return screen.getByRole("button", { name: /save domain/i }) as HTMLButtonElement;
}

beforeEach(() => {
  updateSite.mockResolvedValue({
    id: "site-1",
    domain: "new.example.eu",
    verifiedAt: null,
  });
});

afterEach(() => {
  cleanup();
  updateSite.mockReset();
  toastSuccess.mockReset();
});

describe("RenameSiteCard", () => {
  test("seeds the field from the current domain", () => {
    renderCard({ domain: "shop.example.eu" });

    expect(domainField().value).toBe("shop.example.eu");
  });

  test("Save is disabled until the domain is edited", () => {
    renderCard();

    expect(saveButton().disabled).toBe(true);
  });

  test("saving normalizes the input, sends it, and confirms with a toast", async () => {
    renderCard();

    // Scheme, casing and path are paste artifacts the client normalizes before sending.
    fireEvent.change(domainField(), {
      target: { value: "https://New.Example.EU/pricing" },
    });
    fireEvent.click(saveButton());

    await waitFor(() =>
      expect(updateSite).toHaveBeenCalledWith("site-1", {
        domain: "new.example.eu",
      }),
    );
    await waitFor(() => expect(toastSuccess).toHaveBeenCalled());
  });

  test("an invalid domain never reaches the endpoint", async () => {
    renderCard();

    fireEvent.change(domainField(), { target: { value: "not-a-domain" } });
    fireEvent.click(saveButton());

    expect(await screen.findByText(/valid domain/i)).toBeDefined();
    expect(updateSite).not.toHaveBeenCalled();
  });

  test("a duplicate domain surfaces the backend error", async () => {
    updateSite.mockRejectedValue(
      new ApiError("Domain already registered", 409, "DOMAIN_ALREADY_REGISTERED"),
    );
    renderCard();

    fireEvent.change(domainField(), { target: { value: "taken.example.eu" } });
    fireEvent.click(saveButton());

    expect(await screen.findByRole("alert")).toBeDefined();
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  test("warns that renaming resets verification only when the site is verified", () => {
    const { rerender } = renderCard({ isVerified: true });
    const hint = screen.getByText(/restarts verification/i);
    // The consequence must be announced on focus, not just shown — link it to the input.
    expect(domainField().getAttribute("aria-describedby")).toContain(hint.id);

    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <NextIntlClientProvider locale="en" messages={en}>
          <RenameSiteCard
            siteId="site-1"
            domain="old.example.eu"
            isVerified={false}
          />
        </NextIntlClientProvider>
      </QueryClientProvider>,
    );
    expect(screen.queryByText(/restarts verification/i)).toBeNull();
  });
});
