-- Tombstone instead of a physical DELETE (dev plan DP-29): deleteShop marks the row with deleted_at, and the Shop
-- entity only ever sees rows where it is NULL (@SQLRestriction). The row stays for history and audits (what did
-- the shop list look like yesterday?), and a deleted shop can be restored with a plain UPDATE.
ALTER TABLE shop ADD COLUMN deleted_at TIMESTAMPTZ;

-- Every read filters on deleted_at IS NULL; partial indexes keep the lookups on active shops only, so tombstones
-- never slow them down. They replace the full indexes from V1.
DROP INDEX idx_shop_terminal;
DROP INDEX idx_shop_category;
DROP INDEX idx_shop_node;
CREATE INDEX idx_shop_active_terminal ON shop (terminal) WHERE deleted_at IS NULL;
CREATE INDEX idx_shop_active_category ON shop (category) WHERE deleted_at IS NULL;
CREATE INDEX idx_shop_active_node ON shop (node_id) WHERE deleted_at IS NULL;
