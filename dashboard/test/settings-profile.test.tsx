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
import { ProfileNameCard } from "@/components/settings/profile-name-card";
import en from "../messages/en.json";

// Mock the API client, not the hook: the real `useUpdateName` (and TanStack Query) then drive the
// mutation state the card derives its UI from — isPending/isSuccess/isError/reset all behave for real.
const updateProfileName = vi.fn();
let meName: string | null = null;

vi.mock("@/lib/api/account", () => ({
  updateProfileName: (name: string) => updateProfileName(name),
}));

// `useUpdateName` imports ME_QUERY_KEY from here, so the mock must re-export it alongside `useMe`.
vi.mock("@/hooks/use-auth", () => ({
  ME_QUERY_KEY: ["me"],
  useMe: () => ({
    data: { id: "u1", email: "a@b.co", name: meName, locale: "en", verifiedAt: null },
  }),
}));

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={en}>
        <ProfileNameCard />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function nameField(): HTMLInputElement {
  return screen.getByLabelText(/display name/i) as HTMLInputElement;
}

function saveButton(): HTMLButtonElement {
  return screen.getByRole("button", { name: /save/i }) as HTMLButtonElement;
}

beforeEach(() => {
  meName = null;
  updateProfileName.mockResolvedValue({
    id: "u1",
    email: "a@b.co",
    name: "Ada Lovelace",
    locale: "en",
    verifiedAt: null,
  });
});

afterEach(() => {
  cleanup();
  updateProfileName.mockReset();
});

describe("ProfileNameCard", () => {
  test("seeds the field from the current account name", () => {
    meName = "Grace Hopper";
    renderCard();

    expect(nameField().value).toBe("Grace Hopper");
  });

  test("Save is disabled until the field is edited", () => {
    renderCard();

    expect(saveButton().disabled).toBe(true);
  });

  test("saving sends the typed name and confirms success", async () => {
    renderCard();

    fireEvent.change(nameField(), { target: { value: "Ada Lovelace" } });
    fireEvent.click(saveButton());

    await waitFor(() =>
      expect(updateProfileName).toHaveBeenCalledWith("Ada Lovelace"),
    );
    expect((await screen.findByRole("status")).textContent).toMatch(/saved/i);
  });

  test("editing after a save clears the saved confirmation", async () => {
    meName = "Ada Lovelace";
    renderCard();

    fireEvent.change(nameField(), { target: { value: "Ada L." } });
    fireEvent.click(saveButton());
    await waitFor(() => expect(updateProfileName).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByRole("status").textContent).toMatch(/saved/i),
    );

    fireEvent.change(nameField(), { target: { value: "Ada Lo." } });
    await waitFor(() =>
      expect(screen.getByRole("status").textContent).toBe(""),
    );
  });

  test("clearing the field submits an empty name to erase it", async () => {
    meName = "Ada Lovelace";
    renderCard();

    fireEvent.change(nameField(), { target: { value: "" } });
    fireEvent.click(saveButton());

    await waitFor(() => expect(updateProfileName).toHaveBeenCalledWith(""));
  });

  test("a name over the length bound never reaches the endpoint", async () => {
    renderCard();

    fireEvent.change(nameField(), { target: { value: "x".repeat(121) } });
    fireEvent.click(saveButton());

    expect(await screen.findByText(/120 characters or fewer/i)).toBeDefined();
    expect(updateProfileName).not.toHaveBeenCalled();
  });
});
