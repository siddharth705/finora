// Ported verbatim from frontend/src/lib/maskPhone.ts -- mirrors the backend's PhoneMasking.mask()
// exactly (3 trailing visible digits, "+" preserved when present). Used where a real phone number
// is in hand client-side but only a masked version should be shown (e.g. "we sent a code to
// +•••••••••705").
export function maskPhone(phone: string): string {
  const hasCountryCodePrefix = phone.startsWith('+');
  const prefix = hasCountryCodePrefix ? '+' : '';
  const digits = hasCountryCodePrefix ? phone.slice(1) : phone;
  const VISIBLE_SUFFIX_LENGTH = 3;
  if (digits.length <= VISIBLE_SUFFIX_LENGTH) return phone;
  const visible = digits.slice(-VISIBLE_SUFFIX_LENGTH);
  return prefix + '•'.repeat(digits.length - VISIBLE_SUFFIX_LENGTH) + visible;
}
