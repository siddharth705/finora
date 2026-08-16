// Was defined independently in MerchantReview.tsx, LearningQueue.tsx, and MerchantIntelligence.tsx
// -- same format string, three copies to keep in sync by hand. One shared definition here instead.
//
// Absolute timestamp, not "3 hours ago" -- an operator correlating a row against a deploy or an
// incident timeline needs a value they can compare to a log line.
export function formatWhen(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '—';
}
