-- V1 gave `theme` a default of 'ledger', a value that was never one of the frontend's actual
-- theme options (light/dark/system — see src/context/ThemeContext.tsx) now that theme switching
-- is really wired up in the UI instead of just being persisted server-side and ignored. Anything
-- other than 'light'/'dark'/'system' still gets normalized to 'system' defensively on the
-- frontend, but there's no reason for new signups or existing 'ledger' rows to carry a value
-- that was always a placeholder.
ALTER TABLE users ALTER COLUMN theme SET DEFAULT 'system';
UPDATE users SET theme = 'system' WHERE theme = 'ledger';
