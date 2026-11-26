/**
 * Common ISO 3166-1 alpha-2 codes offered as suggestions (D-8). Suggestions only: the user may
 * type any 2-letter code, and the backend's allowlist (`CountryCodes`) decides (400 if unknown).
 */
export const COUNTRY_SUGGESTIONS: readonly { code: string; name: string }[] = [
  { code: 'US', name: 'United States' },
  { code: 'GB', name: 'United Kingdom' },
  { code: 'CA', name: 'Canada' },
  { code: 'IN', name: 'India' },
  { code: 'DE', name: 'Germany' },
  { code: 'FR', name: 'France' },
  { code: 'ES', name: 'Spain' },
  { code: 'IT', name: 'Italy' },
  { code: 'NL', name: 'Netherlands' },
  { code: 'IE', name: 'Ireland' },
  { code: 'AU', name: 'Australia' },
  { code: 'JP', name: 'Japan' },
  { code: 'SG', name: 'Singapore' },
  { code: 'AE', name: 'United Arab Emirates' },
  { code: 'MX', name: 'Mexico' },
  { code: 'BR', name: 'Brazil' },
]
