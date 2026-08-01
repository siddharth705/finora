ALTER TABLE import_sessions ADD COLUMN layout_metadata_json TEXT;
ALTER TABLE import_sessions ADD COLUMN layout_fingerprint VARCHAR(20);
ALTER TABLE import_sessions ADD COLUMN activated_capabilities_json TEXT;

ALTER TABLE statement_imports ADD COLUMN layout_metadata_json TEXT;
ALTER TABLE statement_imports ADD COLUMN layout_fingerprint VARCHAR(20);
ALTER TABLE statement_imports ADD COLUMN activated_capabilities_json TEXT;
