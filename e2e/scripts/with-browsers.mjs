#!/usr/bin/env node
/**
 * Runs Playwright with the cross-browser and responsive projects defined.
 *
 * They are gated behind FINORA_E2E_BROWSERS in playwright.config.ts rather than merely documented
 * as "opt-in", because `npx playwright test` with no arguments runs every project that exists --
 * a comment cannot make a project optional. A three-line wrapper is the price of not needing
 * cross-env just to set one variable on Windows and POSIX alike.
 */
import { spawn } from 'node:child_process';

const child = spawn(
  process.platform === 'win32' ? 'npx.cmd' : 'npx',
  ['playwright', 'test', ...process.argv.slice(2)],
  { stdio: 'inherit', env: { ...process.env, FINORA_E2E_BROWSERS: '1' } }
);
child.on('exit', (code) => process.exit(code ?? 1));
