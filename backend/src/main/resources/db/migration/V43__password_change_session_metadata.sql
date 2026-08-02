ALTER TABLE password_change_sessions ADD COLUMN verification_provider VARCHAR(32);
ALTER TABLE password_change_sessions ADD COLUMN verified_phone_number VARCHAR(20);
ALTER TABLE password_change_sessions ADD COLUMN signed_out_other_devices BOOLEAN;
