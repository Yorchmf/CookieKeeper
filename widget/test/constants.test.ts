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
});
