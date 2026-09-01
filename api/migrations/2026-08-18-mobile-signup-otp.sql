CREATE TABLE IF NOT EXISTS user_otp_verifications (
id int(11) NOT NULL AUTO_INCREMENT,
mobile varchar(30) NOT NULL,
purpose varchar(30) NOT NULL DEFAULT 'signup',
otp_hash varchar(255) NOT NULL,
expires_at datetime NOT NULL,
verified_at datetime DEFAULT NULL,
attempt_count int(11) NOT NULL DEFAULT 0,
created_at timestamp NOT NULL DEFAULT current_timestamp(),
updated_at timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
PRIMARY KEY (id),
UNIQUE KEY uniq_mobile_purpose (mobile, purpose),
KEY idx_mobile (mobile),
KEY idx_purpose (purpose),
KEY idx_expires_at (expires_at),
KEY idx_verified_at (verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
