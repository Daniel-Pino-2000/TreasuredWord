-- Google sign-in (#112). An account can now be signed in to with a password, with Google, or
-- both (an existing password account is linked the first time its owner uses Google with the
-- same email).

-- Google's permanent ID for the account ("sub" in its ID token). Emails can change on Google's
-- side; this can't, so it's what finds the account on later Google sign-ins.
ALTER TABLE users ADD COLUMN google_subject TEXT NULL;
CREATE UNIQUE INDEX users_google_subject_unique ON users (google_subject);

-- Accounts made with Google have no password.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- But every account keeps at least one way in.
ALTER TABLE users ADD CONSTRAINT users_has_sign_in_method
    CHECK (password_hash IS NOT NULL OR google_subject IS NOT NULL);
