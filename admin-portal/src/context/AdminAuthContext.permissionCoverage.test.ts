import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ADMIN_PORTAL_PERMISSIONS } from './AdminAuthContext';

/**
 * Regression guard for a real bug: RELATIONSHIP_MANAGE (V47__relationship_manage_permission.sql)
 * gates RelationshipsSection (see pages/user-detail/RelationshipsSection.tsx, rendered from
 * UserDetail.tsx behind `hasPermission('RELATIONSHIP_MANAGE')`) but was never added to
 * ADMIN_PORTAL_PERMISSIONS -- the list AdminAuthContext.loadAccess() uses to decide whether an
 * account may even open the admin portal at all. A role holding RELATIONSHIP_MANAGE and nothing
 * else in that list would be rejected at login with "This account doesn't have any admin
 * permissions," even though the backend (V47) explicitly grants it to ADMIN/SUPER_ADMIN as a real,
 * admin-only capability.
 *
 * Rather than re-patching this one permission, this scans every `hasPermission('X')` /
 * `permission="X"` / `permission: 'X'` site under src/pages and src/components -- the only places
 * this app gates a page, section, or sidebar entry on a permission -- and asserts each one is
 * covered by ADMIN_PORTAL_PERMISSIONS. The next permission introduced for a new admin-only section
 * and wired into a page's gating, but never added to this list, now fails a fast unit test instead
 * of silently locking out whichever role ends up holding only that permission.
 */

const THIS_DIR = dirname(fileURLToPath(import.meta.url));
const SRC_DIR = dirname(THIS_DIR); // .../admin-portal/src
const SCAN_DIRS = ['pages', 'components'].map((d) => join(SRC_DIR, d));

// hasPermission('X') (hook calls), permission="X" / permission={"X"} (RequirePermission JSX prop),
// and permission: 'X' (Sidebar.tsx's links[] objects) -- the three shapes this app gates on today.
const PERMISSION_PATTERN = /hasPermission\('([A-Z][A-Z0-9_]*)'\)|permission=\{?"([A-Z][A-Z0-9_]*)"\}?|permission:\s*'([A-Z][A-Z0-9_]*)'/g;

function collectSourceFiles(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      files.push(...collectSourceFiles(full));
      continue;
    }
    // Test files legitimately reference permission strings in assertions/mocks (e.g.
    // ProtectedRoute.test.tsx's `permission="USER_VIEW"` fixture) -- only real gating sites count.
    if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) {
      files.push(full);
    }
  }
  return files;
}

function permissionsReferencedInApp(): Set<string> {
  const found = new Set<string>();
  for (const dir of SCAN_DIRS) {
    for (const file of collectSourceFiles(dir)) {
      const content = readFileSync(file, 'utf8');
      for (const match of content.matchAll(PERMISSION_PATTERN)) {
        const permission = match[1] ?? match[2] ?? match[3];
        if (permission) found.add(permission);
      }
    }
  }
  return found;
}

describe('ADMIN_PORTAL_PERMISSIONS coverage', () => {
  it('includes every permission that actually gates a page, section, or sidebar entry', () => {
    const referenced = permissionsReferencedInApp();
    // Sanity check on the scan itself -- if this list is ever empty, the file-walk broke silently
    // (wrong directory, extension filter too strict) and the real assertion below would pass
    // vacuously without checking anything.
    expect(referenced.size).toBeGreaterThan(10);

    const missing = [...referenced].filter((p) => !ADMIN_PORTAL_PERMISSIONS.includes(p));
    expect(missing).toEqual([]);
  });
});
