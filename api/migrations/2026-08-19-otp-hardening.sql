ALTER TABLE `user_otp_verifications`
  ADD COLUMN `status` ENUM('pending','sent','failed','verified','expired','used') NOT NULL DEFAULT 'pending' AFTER `otp_hash`,
  ADD COLUMN `sent_count` INT NOT NULL DEFAULT 0 AFTER `status`,
  ADD COLUMN `last_sent_at` DATETIME DEFAULT NULL AFTER `sent_count`,
  ADD COLUMN `last_error` TEXT DEFAULT NULL AFTER `last_sent_at`,
  ADD COLUMN `provider_response` TEXT DEFAULT NULL AFTER `last_error`;

ALTER TABLE `user_otp_verifications`
  ADD INDEX `idx_status` (`status`),
  ADD INDEX `idx_last_sent_at` (`last_sent_at`);

UPDATE `user_otp_verifications`
SET `status` = CASE
    WHEN `verified_at` IS NOT NULL THEN 'verified'
    WHEN `expires_at` < NOW() THEN 'expired'
    ELSE 'pending'
END
WHERE `status` = 'pending';
