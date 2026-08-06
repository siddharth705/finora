-- Running the import engine on an uploaded document is an ACTION, not a view.
--
-- Layout Studio's reports are gated by PLATFORM_DIAGNOSTICS_VIEW, which V34 introduced
-- specifically as the read-only operational-visibility permission -- its own description says
-- "no configuration-mutation power". Admin analysis does not fit behind it: it accepts an
-- uploaded file, spends real CPU parsing it, and writes a row to statement_analysis_sessions.
-- Reusing a permission whose stated contract is read-only would quietly undo the separation V34
-- was created to establish, and it would do so invisibly -- anyone granted "view diagnostics"
-- would silently also be able to push documents through the engine.
--
-- Deliberately NOT gated by SYSTEM_SETTINGS either. That permission carries platform-wide
-- configuration mutation (registration policy, feature flags); an engineer who needs to study a
-- statement layout should not have to be handed the ability to change how the platform behaves
-- for every user in order to do it. The whole point of the V34 split was to stop bundling
-- unrelated powers, and this is the same argument one level down.
INSERT INTO permissions (name, description) VALUES
    ('ENGINE_ANALYSIS_RUN', 'Upload a statement to the import engine for analysis only -- no import, no transactions, no stored document. Records a diagnostic analysis session.');

-- ADMIN and SUPER_ADMIN, matching every other permission added since V24. SUPER_ADMIN needs its
-- own explicit grant for the reason documented on V24/V25/V28/V29/V30/V34: its V16 "every
-- permission" catch-all was a one-time snapshot, not a standing rule, so a new permission is not
-- picked up by it automatically.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'ENGINE_ANALYSIS_RUN';
