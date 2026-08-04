import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';

// Reusable diagnostic, not a point patch: this config exists specifically to make the bug class
// found across 7 pages in one bug-hunt session -- a .then() chain with no .catch(), leaving a
// page stuck silently on "Loading…" forever after a failed fetch -- fail `npm run lint` instead
// of waiting for another manual audit to find the next one. There was no ESLint setup in this
// project at all before this (the "lint" script in package.json existed but eslint itself was
// never installed), so this is deliberately a minimal, targeted config rather than a full
// style/formatting ruleset -- broadening it further is a separate decision for whoever owns that
// tradeoff, not a side effect of closing this gap.
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/test/**'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    // Registers the plugin the codebase's own pre-existing `// eslint-disable-next-line
    // react-hooks/exhaustive-deps` comments already assume exists (they predate ESLint actually
    // being installed here at all) -- without this, those comments themselves error as
    // "Definition for rule ... was not found".
    plugins: { 'react-hooks': reactHooks },
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
      parserOptions: {
        // Type-aware linting (required for no-floating-promises/no-misused-promises) needs a
        // real tsconfig to resolve types against -- projectService auto-discovers tsconfig.json
        // per-file rather than needing every file's project hardcoded here.
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // The rule this config exists for: flags a Promise created and never awaited/returned/
      // handled with .catch() or .then(_, onRejected) -- exactly the missing-.catch() shape that
      // left Budgets/Goals/Analytics/ActivityTimeline/LearningEngine/Reports/Setup silently stuck
      // loading on a failed initial fetch.
      '@typescript-eslint/no-floating-promises': 'error',
      // The sibling bug shape: passing an async function where a sync callback is expected
      // silently drops any rejection instead of surfacing it. `attributes: false` is the
      // standard React accommodation -- `onClick={async () => {...}}` is idiomatic React (the
      // DOM event system doesn't await handler return values either way) and every one of this
      // codebase's async handlers already has its own try/catch; without this the rule would
      // flag essentially every async click handler in the app for a pattern that isn't the bug
      // this config targets.
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { attributes: false } }],

      // Reusable diagnostic for a second bug class found in the same audit: a JSX `target="_blank"`
      // link with no (or an incomplete) `rel="noopener noreferrer"`. Register.tsx's two Terms/
      // Privacy links opened same-app tabs while leaving `window.opener` pointed back at the
      // in-progress registration form -- reverse-tabnabbing's classic shape, and pre-Chrome-88/
      // Firefox-79 also kept the new tab on the same process as this one. No eslint-plugin-react
      // rule (react/jsx-no-target-blank) is pulled in for this alone -- that plugin would add a
      // whole peer-dependency surface for one rule this codebase already has the tools to express
      // natively via no-restricted-syntax, consistent with "minimal, targeted config" above.
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "JSXOpeningElement:has(JSXAttribute[name.name='target'][value.value='_blank']):not(:has(JSXAttribute[name.name='rel'][value.value=/noopener/]))",
          message:
            'target="_blank" must include rel="noopener noreferrer". Without it the opened tab keeps a window.opener handle back to this page (reverse tabnabbing), even for an internal route.',
        },
      ],

      // Registering the plugin (above) without turning its rules on would leave the codebase's
      // pre-existing `// eslint-disable-next-line react-hooks/exhaustive-deps` comments pointing
      // at an inert rule -- silencing nothing, and reporting as "unused directive" noise instead.
      // 'warn' rather than 'error': a genuinely stale dependency array is a real bug class of its
      // own, worth surfacing, but auditing every hook's deps across the app is a separate,
      // deliberate piece of work from this promise-handling diagnostic.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // Deliberately relaxed rather than adopting the full type-checked preset at its defaults:
      // this codebase makes routine, intentional use of these patterns (API responses, event
      // handlers, catch blocks), and turning them on now would produce a wall of unrelated
      // findings that have nothing to do with the promise-handling gap this config targets.
      // Revisit as a separate, deliberate decision if stricter typing discipline is wanted later.
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/restrict-template-expressions': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/require-await': 'off',
      '@typescript-eslint/no-empty-function': 'off',
      '@typescript-eslint/no-redundant-type-constituents': 'off',
      '@typescript-eslint/no-base-to-string': 'off',
      '@typescript-eslint/prefer-promise-reject-errors': 'off',
      '@typescript-eslint/no-unnecessary-type-assertion': 'off',
    },
  },
);
