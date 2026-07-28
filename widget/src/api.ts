/**
 * Consent event reporting → POST /api/v1/consent.
 * Uses navigator.sendBeacon so events survive page unload; falls back to
 * fetch(keepalive). Fire-and-forget: the widget never blocks the host page
 * or surfaces network errors to the visitor.
 */

import { API_BASE } from './constants';
import type { ConsentDecision } from './consent-mode';

export interface ConsentEventPayload {
  siteKey: string;
  action: 'accept_all' | 'reject_all' | 'custom';
  categories: ConsentDecision;
  lang: string;
  ts: number;
}

export function sendConsentEvent(payload: ConsentEventPayload): void {
  const url = `${API_BASE}/api/v1/consent`;
  const body = JSON.stringify(payload);

  try {
    if (typeof navigator.sendBeacon === 'function') {
      const ok = navigator.sendBeacon(
        url,
        new Blob([body], { type: 'application/json' }),
      );
      if (ok) return;
    }
    void fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
    }).catch(() => undefined);
  } catch {
    // Fail silent-safe (ARCHITECTURE.md §9) — never break the host page.
  }
}
