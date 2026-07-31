-- Registration only ever checked for a duplicate EMAIL, never a duplicate phone number -- two
-- different accounts could share the same mobile number. This matters more now than before:
-- email-or-phone login (AuthService.resolveEmailForLogin) does a findByPhoneNumber lookup
-- expecting at most one match, which a duplicate phone number would make ambiguous. The
-- application-level check in AuthService.register() is the primary defense (a clean error
-- message); this constraint is the backstop so the same problem can never be introduced by a
-- direct DB write or a future code path that forgets the application-level check.
ALTER TABLE users ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);
