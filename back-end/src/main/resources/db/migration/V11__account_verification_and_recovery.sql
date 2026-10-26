ALTER TABLE intelligence.users ADD COLUMN email_verified_at timestamptz;

-- Existing accounts predate verification and remain usable after this migration.
UPDATE intelligence.users SET email_verified_at = created_at;

CREATE TABLE intelligence.auth_challenges (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES intelligence.users(id) ON DELETE CASCADE,
 purpose varchar(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
 digest char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX auth_challenge_active_user_purpose
 ON intelligence.auth_challenges(user_id, purpose) WHERE consumed_at IS NULL;
CREATE INDEX auth_challenge_expiry ON intelligence.auth_challenges(expires_at)
 WHERE consumed_at IS NULL;

-- The email body is encrypted in the application before it enters this durable queue.
CREATE TABLE intelligence.auth_mail_outbox (
 id uuid PRIMARY KEY,
 challenge_id uuid NOT NULL REFERENCES intelligence.auth_challenges(id) ON DELETE CASCADE,
 recipient varchar(254) NOT NULL,
 subject varchar(160) NOT NULL,
 nonce bytea NOT NULL,
 ciphertext bytea NOT NULL,
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_mail_outbox_next ON intelligence.auth_mail_outbox(next_attempt_at, created_at);
