#!/usr/bin/env bash
set -euo pipefail

# apply-migrations.sh
# Usage:
#   ./scripts/apply-migrations.sh -h HOST -P PORT -u USER -d DATABASE
# The script will prompt for the DB password, create a dump under ./backups,
# then apply the migrations in order. Designed for pasting into Cloud Shell.

MIGRATIONS=(
  "api/migrations/2026-08-12-sms-and-surplus-proof.sql"
  "api/migrations/2026-08-18-mobile-signup-otp.sql"
  "api/migrations/2026-08-19-otp-hardening.sql"
)

show_usage() {
  cat <<EOF
Usage: $0 -h HOST -P PORT -u USER -d DATABASE
Example:
  $0 -h 127.0.0.1 -P 3307 -u aokwi -d kitchen
EOF
}

HOST="127.0.0.1"
PORT="3306"
USER="root"
DB=""

while getopts ":h:P:u:d:" opt; do
  case ${opt} in
    h ) HOST=$OPTARG ;;
    P ) PORT=$OPTARG ;;
    u ) USER=$OPTARG ;;
    d ) DB=$OPTARG ;;
    \? ) show_usage; exit 1 ;;
  esac
done

if [ -z "$DB" ]; then
  echo "ERROR: database (-d) is required"
  show_usage
  exit 1
fi

read -s -p "Enter password for MySQL user $USER: " MYSQL_PWD
echo

TS=$(date +%Y%m%d-%H%M%S)
BACKUP_DIR="backups"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/backup-$DB-$TS.sql"

echo "Backing up database '$DB' to $BACKUP_FILE ..."
if ! mysqldump -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" --single-transaction --routines --triggers "$DB" > "$BACKUP_FILE"; then
  echo "ERROR: Backup failed. Aborting."
  exit 2
fi

echo "Backup completed. File: $BACKUP_FILE"

# Apply migrations one by one
for m in "${MIGRATIONS[@]}"; do
  if [ ! -f "$m" ]; then
    echo "ERROR: Migration file not found: $m"
    echo "Make sure you're running this script from the repository root."
    exit 3
  fi

  echo "\n--- Applying migration: $m ---"
  if ! mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" "$DB" < "$m"; then
    echo "ERROR: Migration $m failed. Aborting."
    echo "You can restore the DB from the backup: mysql -h $HOST -P $PORT -u $USER -p $DB < $BACKUP_FILE"
    exit 4
  fi
  echo "Migration $m applied successfully."
done

# Verification queries
echo "\n--- Verification queries ---"
# 1) notification_queue channel enum
echo "notification_queue.channel column type:"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" -N -s -e \
  "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_queue' AND COLUMN_NAME='channel';" "$DB" || true

# 2) user_notifications sms columns
echo "\nuser_notifications sms columns (name and type):"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" -N -s -e \
  "SHOW COLUMNS FROM user_notifications LIKE 'sms%';" "$DB" || true

# 3) user_otp_verifications columns and status counts
echo "\nuser_otp_verifications columns (brief):"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" -N -s -e \
  "SHOW COLUMNS FROM user_otp_verifications;" "$DB" || true

echo "\nuser_otp_verifications status counts:"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" -N -s -e \
  "SELECT status, COUNT(*) FROM user_otp_verifications GROUP BY status;" "$DB" || true

cat <<EOF

Migrations applied successfully.
Next steps:
 - Deploy the updated application code (git push / Render deploy).
 - Run the notification worker once to confirm queue processing:
     php api/cron/process_notifications.php
 - Test OTP flow: request OTP (use your phone), verify the DB row (status='sent') and then verify.

If anything fails, restore the DB from the backup:
  mysql -h $HOST -P $PORT -u $USER -p $DB < $BACKUP_FILE

EOF
