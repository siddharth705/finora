import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';

// Mirrors frontend/'s eslint.config.js -- same reasoning applies directly, this is the same
// stack. There was no ESLint setup in this project at all before this (the "lint" script in
// package.json existed but eslint itself was never installed), so this is deliberately a
// minimal, targeted config aimed at the missing-.catch()/unhandled-promise bug class rather than
// a full style/formatting ruleset -- broadening it further is a separate decision for whoever
// owns that tradeoff, not a side effect of closing this gap.
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/test/**'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    // Registers the plugin so any pre-existing `// eslint-disable-next-line
    // react-hooks/exhaustive-deps` comments resolve to a real rule instead of erroring as
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
      // handled with .catch() or .then(_, onRejected).
      '@typescript-eslint/no-floating-promises': 'error',
      // The sibling bug shape: passing an async function where a sync callback is expected
      // silently drops any rejection instead of surfacing it. `attributes: false` is the
      // standard React accommodation -- `onClick={async () => {...}}` is idiomatic React (the
      // DOM event system doesn't await handler return values either way).
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { attributes: false } }],

      'react-hooks/rules-of-hooks': 'error',
      // 'warn' rather than 'error': a genuinely stale dependency array is a real bug class of its
      // own, worth surfacing, but auditing every hook's deps across the app is a separate,
      // deliberate piece of work from this promise-handling diagnostic.
      'react-hooks/exhaustive-deps': 'warn',

      // Deliberately relaxed rather than adopting the full type-checked preset at its defaults:
      // this codebase makes routine, intentional use of these patterns, and turning them on now
      // would produce a wall of unrelated findings that have nothing to do with the
      // promise-handling gap this config targets. Revisit as a separate, deliberate decision if
      // stricter typing discipline is wanted later.
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
      // Known false-positive shape: expect(window.confirm).toHaveBeenCalled() never actually
      // invokes the method, so there's no real unbound-`this` risk -- just noise in test files
      // that assert on a mocked global/object method this way.
      '@typescript-eslint/unbound-method': 'off',
    },
  },
);
