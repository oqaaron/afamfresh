-- Idempotent migration: add OTP tracking columns if they don't exist
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
  WHERE TABLE_NAME = 'user_otp_verifications' AND COLUMN_NAME = 'status');

-- Only proceed if status column doesn't exist
SET @sql := IF(@col_exists = 0, 
  'ALTER TABLE user_otp_verifications
   ADD COLUMN status ENUM("pending","sent","failed","verified","expired","used") NOT NULL DEFAULT "pending" AFTER otp_hash,
   ADD COLUMN sent_count INT NOT NULL DEFAULT 0 AFTER status,
   ADD COLUMN last_sent_at DATETIME DEFAULT NULL AFTER sent_count,
   ADD COLUMN last_error TEXT DEFAULT NULL AFTER last_sent_at,
   ADD COLUMN provider_response TEXT DEFAULT NULL AFTER last_error',
  'SELECT 1');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add indexes if they don't already exist
SET @idx_status_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
  WHERE TABLE_NAME = 'user_otp_verifications' AND INDEX_NAME = 'idx_status');

SET @idx_sent_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
  WHERE TABLE_NAME = 'user_otp_verifications' AND INDEX_NAME = 'idx_last_sent_at');

SET @sql_idx := IF(@idx_status_exists = 0, 
  'ALTER TABLE user_otp_verifications ADD INDEX idx_status (status)',
  'SELECT 1');

PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;

SET @sql_idx2 := IF(@idx_sent_exists = 0,
  'ALTER TABLE user_otp_verifications ADD INDEX idx_last_sent_at (last_sent_at)',
  'SELECT 1');

PREPARE stmt_idx2 FROM @sql_idx2;
EXECUTE stmt_idx2;
DEALLOCATE PREPARE stmt_idx2;

-- Update status if there are any pending records without a status set
UPDATE user_otp_verifications
SET status = CASE
WHEN verified_at IS NOT NULL THEN 'verified'
WHEN expires_at < NOW() THEN 'expired'
ELSE 'pending'
END
WHERE status = 'pending' OR status IS NULL;
