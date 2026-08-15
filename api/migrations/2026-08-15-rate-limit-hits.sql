-- =============================================================
-- A simple app-level rate limiter, no new infrastructure required
-- =============================================================
-- login/forgot_password/reset_password and the public delivery-fee quote
-- endpoint had no rate limiting at all -- unlimited attempts against any
-- account, from any IP. No WAF/Cloudflare sits in front of Render today, so
-- this is a plain DB-backed sliding-window counter rather than an infra
-- change. See includes/rate_limit.php.
--
-- One row per hit rather than one row per bucket with a counter: a hit
-- naturally expires by falling out of the time window, no separate reset
-- job needed, and rateLimited() prunes expired rows for its own bucket on
-- every call, so the table doesn't grow unbounded on its own.
-- =============================================================

CREATE TABLE `rate_limit_hits` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `bucket` VARCHAR(150) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_bucket_time` (`bucket`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
