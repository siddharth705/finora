import { API_ORIGIN, backendReachable } from './config';
import { databaseReachable, count } from './db';
import { completeSetupIfRequired } from './setup';

/**
 * Fails the run before a single test does, when the stack is not there.
 *
 * The alternative — letting the specs discover it — produces a wall of timeouts that all say
 * "element not found", none of which mention the actual cause. A test harness that cannot tell
 * "the product is broken" from "nothing is running" is worse than no harness, because it teaches
 * people that red means nothing.
 */
export default async function globalSetup() {
  const problems: string[] = [];

  if (!(await backendReachable())) {
    problems.push(
      `  • No healthy backend at ${API_ORIGIN}.\n` +
        `    Start the throwaway stack:  npm run stack:up  (see e2e/README.md)`
    );
  }

  if (!(await databaseReachable())) {
    problems.push(
      `  • Cannot reach the test database.\n` +
        `    The suite seeds accounts and cross-checks financial totals directly against it —\n` +
        `    see fixtures/db.ts for why that is necessary rather than lazy.`
    );
  }

  if (problems.length) {
    throw new Error(
      `\nThe e2e stack is not ready:\n\n${problems.join('\n\n')}\n\n` +
        `These tests drive a real browser against a real backend and a real database. They are not\n` +
        `mocked, and they are not meant to be — the milestone they cover is about what actually\n` +
        `lands in a ledger.\n`
    );
  }

  // A fresh database has never been through first-run setup, so the admin portal serves the
  // installation wizard instead of a login form and every admin test times out against a screen it
  // was not looking for. Driven through the product's own flow -- see fixtures/setup.ts.
  await completeSetupIfRequired();

  // A loud warning rather than a failure. Pointing the suite at a database with existing data is
  // legitimate for debugging, but several assertions read platform-wide counts (the admin queue,
  // the merchant review list), and pre-existing rows will make those noisy. Better to say so than
  // to have someone chase a phantom.
  const users = await count('select count(*) from users');
  if (users > 2) {
    console.warn(
      `\n[e2e] The target database already holds ${users} users. The suite seeds its own accounts\n` +
        `      and scopes almost every assertion to them, but admin-portal list views are\n` +
        `      platform-wide and will show the others too. For a clean read, run against a fresh\n` +
        `      database: npm run stack:reset\n`
    );
  }
}
