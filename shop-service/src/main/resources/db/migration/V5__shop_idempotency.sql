-- Idempotent createShop (dev plan DP-30): the client's key for one intended creation. A repeated createShop with the
-- same key returns the shop the first call created. The constraint covers deleted shops too, so a key can never be
-- reused after its shop has been deleted (tombstone, V4).
ALTER TABLE shop ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE shop ADD CONSTRAINT uq_shop_idempotency_key UNIQUE (idempotency_key);
