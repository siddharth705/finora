/**
 * Where the suite points.
 *
 * Defaults describe the "fresh stack" the milestone brief asks for — a throwaway Postgres and a
 * backend of its own, deliberately NOT the 8080/5432 pair a developer already has running, so a
 * test run can never quietly assert against yesterday's data or a backend built from another
 * branch. Every value is overridable for CI or for pointing at an existing environment.
 *
 * See e2e/README.md for how to bring that stack up.
 */

export const API_ORIGIN = process.env.FINORA_E2E_API_ORIGIN ?? 'http://localhost:8081';
export const API_BASE = `${API_ORIGIN}/api/v1`;

export const USER_APP = process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173';
export const ADMIN_APP = process.env.FINORA_E2E_ADMIN_APP ?? 'http://localhost:5174';

export async function backendReachable(): Promise<boolean> {
  try {
    const response = await fetch(`${API_ORIGIN}/actuator/health`);
    if (!response.ok) return false;
    const body = await response.json().catch(() => null);
    return body?.status === 'UP';
  } catch {
    return false;
  }
}
