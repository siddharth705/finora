/**
 * How long an unfinished import has left before the server drops it.
 *
 * Staged sessions expire (`ImportSessionService.listActiveSessions` filters on `expiresAt`), so an
 * unfinished import is not a permanent to-do — it disappears on its own. Offering to resume one
 * without saying that sets the user up to come back tomorrow and find the work gone, which is the
 * same class of silent loss the resume flow exists to prevent.
 */
export function expiresInLabel(expiresAt: string, now: number = Date.now()): string {
  const msLeft = new Date(expiresAt).getTime() - now;

  // A session the client still holds but the server would already reject. The list is fetched, not
  // live, so this is reachable simply by leaving the screen open — say so rather than offering a
  // resume that will fail.
  if (!Number.isFinite(msLeft) || msLeft <= 0) return 'Expired';

  const minutes = Math.floor(msLeft / 60_000);
  if (minutes < 60) {
    // Deliberately not "in 0 minutes" for the final seconds.
    return minutes <= 1 ? 'Expires in under a minute' : `Expires in ${minutes} minutes`;
  }

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return hours === 1 ? 'Expires in 1 hour' : `Expires in ${hours} hours`;

  const days = Math.floor(hours / 24);
  return days === 1 ? 'Expires in 1 day' : `Expires in ${days} days`;
}

/** True when the server would already refuse to resume this session. */
export function hasExpired(expiresAt: string, now: number = Date.now()): boolean {
  const t = new Date(expiresAt).getTime();
  return !Number.isFinite(t) || t <= now;
}
