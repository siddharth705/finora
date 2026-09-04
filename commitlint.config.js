/**
 * Enforces Conventional Commits (https://www.conventionalcommits.org/) across the whole repo.
 * Scopes are deliberately restricted to the feature/module names used in the codebase itself
 * (see docs/engineering/CODING_STANDARDS.md) so `git log --grep` and changelog generation stay
 * meaningful as the backend moves to feature-based packages.
 */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'scope-enum': [2, 'always', [
      // 'web' means frontend/ AND admin-portal/ together. Added because there was no scope for a
      // change that lands in both, which is a real and recurring shape: they share an API client
      // contract, an auth flow, and a component vocabulary, so error boundaries, the react-router
      // upgrade, route splitting and the accessibility baseline each touched the pair. Those
      // commits went out unscoped, which is exactly what scope-enum exists to prevent.
      //
      // Use the specific scope when only one app changes -- 'web' is for genuinely paired work,
      // not a shortcut for "some frontend thing".
      'backend', 'frontend', 'admin-portal', 'web', 'mobile', 'mobile-api', 'shared',
      'transactions', 'accounts', 'budgets', 'goals', 'imports', 'analytics',
      'reports', 'rules', 'settings', 'users', 'auth', 'security',
      // In-product help requests and product feedback (com.finora.support, and the user-facing
      // and admin screens over it). Its own scope rather than mapping onto 'backend'/'frontend'
      // because the feature is deliberately split across both plus 'db' and 'admin-portal', so
      // without it no single scope groups the work and `git log --grep` cannot find it.
      'support',
      'db', 'infra', 'ci', 'docs', 'deps'
    ]],
    'body-max-line-length': [0],
  },
};
