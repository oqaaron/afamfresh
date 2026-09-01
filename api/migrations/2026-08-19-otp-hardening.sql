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

-- Add indexes if they don't exist (MySQL 8.0.13+)
ALTER TABLE user_otp_verifications
ADD INDEX IF NOT EXISTS idx_status (status),
ADD INDEX IF NOT EXISTS idx_last_sent_at (last_sent_at);

-- Update status if there are any pending records without a status set
UPDATE user_otp_verifications
SET status = CASE
WHEN verified_at IS NOT NULL THEN 'verified'
WHEN expires_at < NOW() THEN 'expired'
ELSE 'pending'
END
WHERE status = 'pending' OR status IS NULL;
