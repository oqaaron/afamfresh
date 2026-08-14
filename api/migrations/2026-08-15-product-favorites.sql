-- =============================================================
-- Real favorites, replacing two no-op heart icons
-- =============================================================
-- Two heart icons have existed since the current UI shipped -- one on each
-- HomeScreen product card, one on ProductDetailScreen's header -- and
-- neither has ever done anything. HomeScreen.kt's onClick was literally
-- `{ /* toggle favorite */ }`; ProductDetailScreen.kt's said
-- `{ /* favorite - not wired to backend yet */ }`. No favorites table has
-- ever existed. This is that table.
--
-- user_id is INT(11) to match users.id, product_id is INT(100) to match
-- items.id -- getting either wrong breaks the FK below with error 3780,
-- the same mismatch the customer_addresses migration hit this session.
--
-- Applies itself automatically via scripts/run-migrations.php on the next
-- deploy. Nothing to seed by hand -- this is a brand new table, not a
-- repurposed one.
-- =============================================================

CREATE TABLE `product_favorites` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `user_id` INT(11) NOT NULL,
  `product_id` INT(100) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_product` (`user_id`, `product_id`),
  KEY `idx_user` (`user_id`),
  CONSTRAINT `fk_favorites_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_favorites_product` FOREIGN KEY (`product_id`) REFERENCES `items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- Sanity check
-- -------------------------------------------------------------
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'product_favorites'
 ORDER BY ORDINAL_POSITION;
