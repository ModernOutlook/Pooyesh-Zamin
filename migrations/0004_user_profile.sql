-- Add the profile fields required by the web registration and account APIs.
-- Apply once to an existing database after confirming these columns are absent.
ALTER TABLE users ADD COLUMN name TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN account_type TEXT NOT NULL DEFAULT 'home';
ALTER TABLE users ADD COLUMN address TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN area TEXT NOT NULL DEFAULT '';
