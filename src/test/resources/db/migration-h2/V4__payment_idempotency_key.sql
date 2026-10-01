-- ===========================================================================
--  H2 fallback copy of V4__payment_idempotency_key.sql.
--
--  Identical to the production migration in src/main/resources/db/migration
--  except that TIMESTAMPTZ is spelled TIMESTAMP WITH TIME ZONE, because H2's
--  PostgreSQL mode has no TIMESTAMPTZ alias. No other change: the table
--  definitions, CHECK constraints, foreign keys and UNIQUE keys are the real
--  ones, which is what the integration tests assert on.
--
--  Used only when no Docker daemon is available. See AbstractIntegrationTest.
-- ===========================================================================
-- ===========================================================================
--  V4__payment_idempotency_key.sql
--
--  Phase 7 accepts an optional "Idempotency-Key" header on POST /payments so a
--  client that retries after a timeout does not create a second payment.
--
--  Honouring it properly needs somewhere to remember the key, because the
--  business rules alone cannot: two FAILED payments on the same booking are both
--  legitimate, so "has this key been seen" is a question only the stored key can
--  answer.
--
--  NULLs stay distinct in a Postgres unique index, so every payment sent without
--  the header is unaffected - which is why this can be added without touching
--  existing rows.
-- ===========================================================================

ALTER TABLE payments
    ADD COLUMN idempotency_key VARCHAR(120);

CREATE UNIQUE INDEX uq_payments_idempotency_key ON payments (idempotency_key);