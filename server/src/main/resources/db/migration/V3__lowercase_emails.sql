-- Emails are compared without regard to upper/lower case (#114): AuthRoutes.kt lower-cases them
-- on register and login, so the rows stored before that change have to be lower-cased too, or
-- someone who signed up as "Ana@example.com" could no longer sign in at all.

-- Two accounts that differ only by case can't both become "ana@example.com". Stop with a clear
-- message instead of a unique-index error (or a guess about which account to keep); Flyway runs
-- this file in one transaction, so nothing below is applied and the server doesn't start until
-- the duplicates are merged or removed by hand.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(email) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'users has emails that differ only by upper/lower case; resolve them by hand before V3 (#114)';
    END IF;
END $$;

UPDATE users SET email = lower(email) WHERE email <> lower(email);

-- users_email_unique (V1) stays as it is: with every stored email lower case, a plain unique
-- index already blocks "Ana@" next to "ana@", and it still serves the `email = ?` lookups. A
-- unique index on lower(email) instead would only be used by queries written as lower(email) = ?.
-- This check makes the database itself refuse a mixed-case email, so a future code path that
-- forgets to lower-case (Google sign-in, #112; a change-email endpoint) fails loudly instead of
-- quietly creating an account nobody can sign in to.
ALTER TABLE users ADD CONSTRAINT users_email_lowercase CHECK (email = lower(email));
