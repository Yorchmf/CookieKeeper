/**
 * Zero-dependency UUID generation for the widget.
 *
 * CSPRNG-backed, with a non-crypto fallback for locked-down environments: these
 * ids are audit-correlation handles (vid) and idempotency keys (eventKey), never
 * security tokens, so a degraded-random id is acceptable — a dropped or collided
 * id would be worse than a non-crypto one.
 */

import { warn } from './debug';

/**
 * 16 random bytes from the platform CSPRNG. When none exists, fall back to a
 * non-crypto seed so distinct callers don't all collapse onto one constant
 * all-zero UUID (the ids are correlation handles, not secrets).
 */
export function randomBytes16(): Uint8Array {
  const bytes = new Uint8Array(16);
  const cryptoObj = globalThis.crypto;
  if (typeof cryptoObj?.getRandomValues === 'function') {
    cryptoObj.getRandomValues(bytes);
  } else {
    warn('crypto.getRandomValues unavailable; using non-crypto UUID fallback');
    const seed = Date.now();
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256) ^ ((seed >>> (i % 32)) & 0xff);
    }
  }
  return bytes;
}

/** Format 16 bytes as a canonical 8-4-4-4-12 hyphenated UUID string. */
export function toUuidString(bytes: Uint8Array): string {
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'));
  return (
    `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-` +
    `${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-` +
    `${hex.slice(10, 16).join('')}`
  );
}

/**
 * RFC 9562 v7 UUID: a 48-bit big-endian Unix-millisecond timestamp prefix
 * followed by a random tail, so ids minted over time sort in creation order.
 *
 * Used for the consent `eventKey`, which the backend stores as the PRIMARY KEY
 * of `consent_idempotency`. A time-ordered key keeps index inserts on the
 * append edge instead of scattering random-v4 writes across the btree — the
 * same locality rationale the server uses for `consent_events.id` (see V3/V5).
 */
export function uuidv7(): string {
  const bytes = randomBytes16();
  const ms = Date.now();
  // 48-bit timestamp across bytes 0..5, big-endian. Divide-then-modulo (not
  // bit-shifts) because ms exceeds 32 bits and `<<`/`&` would truncate it.
  bytes[0] = Math.floor(ms / 2 ** 40) % 256;
  bytes[1] = Math.floor(ms / 2 ** 32) % 256;
  bytes[2] = Math.floor(ms / 2 ** 24) % 256;
  bytes[3] = Math.floor(ms / 2 ** 16) % 256;
  bytes[4] = Math.floor(ms / 2 ** 8) % 256;
  bytes[5] = ms % 256;
  bytes[6] = (bytes[6]! & 0x0f) | 0x70; // version 7
  bytes[8] = (bytes[8]! & 0x3f) | 0x80; // variant 10xx
  return toUuidString(bytes);
}
