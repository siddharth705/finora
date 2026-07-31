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
      'backend', 'frontend', 'admin-portal', 'mobile-api', 'shared',
      'transactions', 'accounts', 'budgets', 'goals', 'imports', 'analytics',
      'reports', 'rules', 'settings', 'users', 'auth', 'security',
      'db', 'infra', 'ci', 'docs', 'deps'
    ]],
    'body-max-line-length': [0],
  },
};
