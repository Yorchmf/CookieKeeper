import { describe, expect, it } from 'vitest';

import { resolveBases } from '../src/constants';

describe('resolveBases', () => {
  it('maps the prd CDN host to the prd API host', () => {
    expect(resolveBases('https://cdn.complyr.eu/v1.js')).toEqual({
      cdn: 'https://cdn.complyr.eu',
      api: 'https://api.complyr.eu',
    });
  });

  it('maps the dev CDN host to the dev API host', () => {
    expect(resolveBases('https://cdn.dev.complyr.eu/v1.js')).toEqual({
      cdn: 'https://cdn.dev.complyr.eu',
      api: 'https://api.dev.complyr.eu',
    });
  });

  it('uses the same origin for the localhost dev harness', () => {
    expect(resolveBases('http://localhost:5173/src/main.ts')).toEqual({
      cdn: 'http://localhost:5173',
      api: 'http://localhost:5173',
    });
  });

  it('falls back to production when the script src is unavailable', () => {
    expect(resolveBases(null)).toEqual({
      cdn: 'https://cdn.complyr.eu',
      api: 'https://api.complyr.eu',
    });
  });

  it('falls back to production for non-CDN origins', () => {
    expect(resolveBases('https://customer-site.example/complyr.js')).toEqual({
      cdn: 'https://cdn.complyr.eu',
      api: 'https://api.complyr.eu',
    });
  });

  describe('loopback overrides', () => {
    it('applies localhost api/cdn overrides regardless of derived origin', () => {
      expect(
        resolveBases('http://localhost:5173/src/main.ts', {
          api: 'http://localhost:8080',
          cdn: 'http://localhost:8080',
        }),
      ).toEqual({ cdn: 'http://localhost:8080', api: 'http://localhost:8080' });
    });

    it('overrides each base independently, keeping the derived one otherwise', () => {
      expect(
        resolveBases('http://localhost:5173/src/main.ts', {
          api: 'http://127.0.0.1:8080',
        }),
      ).toEqual({ cdn: 'http://localhost:5173', api: 'http://127.0.0.1:8080' });
    });

    it('ignores a non-loopback override so prod embeds cannot be redirected', () => {
      expect(
        resolveBases('https://cdn.complyr.eu/v1.js', {
          api: 'https://evil.example/collect',
        }),
      ).toEqual({ cdn: 'https://cdn.complyr.eu', api: 'https://api.complyr.eu' });
    });

    it('ignores a malformed override value', () => {
      expect(
        resolveBases('https://cdn.complyr.eu/v1.js', { api: 'not-a-url' }),
      ).toEqual({ cdn: 'https://cdn.complyr.eu', api: 'https://api.complyr.eu' });
    });
  });
});
