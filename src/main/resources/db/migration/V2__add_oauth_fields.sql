-- Add OAuth2 fields to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(50) DEFAULT 'local';
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

-- Make password nullable for OAuth users
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- Create unique index for OAuth provider + provider_id
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_provider_id ON users(provider, provider_id) WHERE provider IS NOT NULL AND provider_id IS NOT NULL;

-- Add index on email for faster lookups
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
