import { describe, expect, test } from 'vitest';
import { randomBytes16, toUuidString, uuidv7 } from '../src/uuid';

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('uuidv7', () => {
  test('produces a canonical v7 UUID string', () => {
    const id = uuidv7();
    expect(id).toMatch(UUID_PATTERN);
    // Version nibble (first char of the 3rd group) is 7; variant nibble is 8/9/a/b.
    expect(id[14]).toBe('7');
    expect(id[19]).toMatch(/[89ab]/i);
  });

  test('mints distinct keys on successive calls', () => {
    const keys = new Set(Array.from({ length: 100 }, () => uuidv7()));
    expect(keys.size).toBe(100);
  });

  test('is time-ordered — later timestamps sort lexicographically after earlier ones', () => {
    // The 48-bit ms prefix means string order tracks creation order, which is the
    // whole point of v7 for the server-side PK index. Assert the prefix encodes now.
    const before = Date.now();
    const id = uuidv7();
    const after = Date.now();

    const msHex = id.replace(/-/g, '').slice(0, 12);
    const ms = parseInt(msHex, 16);
    expect(ms).toBeGreaterThanOrEqual(before);
    expect(ms).toBeLessThanOrEqual(after);
  });
});

describe('toUuidString', () => {
  test('formats 16 bytes as 8-4-4-4-12 hyphenated hex', () => {
    const bytes = Uint8Array.from({ length: 16 }, (_v, i) => i);
    expect(toUuidString(bytes)).toBe('00010203-0405-0607-0809-0a0b0c0d0e0f');
  });
});

describe('randomBytes16', () => {
  test('returns 16 bytes', () => {
    expect(randomBytes16()).toHaveLength(16);
  });
});
