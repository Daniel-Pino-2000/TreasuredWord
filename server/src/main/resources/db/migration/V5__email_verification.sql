-- Email verification and "forgot password" (#148, #155). Both work the same way: the server
-- emails a one-time 6-digit code and the person types it back, which proves they own the inbox.

-- When the email was verified; null until then. TIMESTAMP (UTC, no time zone) like every other
-- time column here, since that's what Exposed's timestamp() reads and writes.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP NULL;

-- Google only gives us emails it has verified (GoogleIdTokenVerifier refuses any token without
-- email_verified), so accounts that have signed in with Google, including password accounts
-- linked to Google, already proved they own the inbox. Password-only accounts stay unverified.
UPDATE users SET email_verified_at = created_at WHERE google_subject IS NOT NULL;

-- The codes themselves. Only a hash of each code is stored, like refresh tokens. A 6-digit code
-- has only a million possible values, so the hash alone doesn't protect it the way a refresh
-- token's does: each code also allows only a few wrong tries (attempts) and expires quickly.
-- Deleting an account deletes its codes (ON DELETE CASCADE), like every other table.
CREATE TABLE email_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT email_codes_purpose CHECK (purpose IN ('verify_email', 'reset_password'))
);
-- Every lookup is "this user's newest code for this purpose".
CREATE INDEX email_codes_user_id_idx ON email_codes (user_id);
