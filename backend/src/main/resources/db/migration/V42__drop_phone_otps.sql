-- Architecture change: phone verification (registration, password reset, authenticated password
-- change) now belongs to Firebase Phone Authentication, not this codebase's own OTP generation/
-- storage/verification -- see FirebaseConfig's own doc comment. OTP rows were always short-lived
-- (10-minute expiry, never referenced once verified/expired), so there's no real data to migrate
-- forward; this table's entire purpose no longer exists.
DROP TABLE IF EXISTS phone_otps;
