#!/bin/bash
# Build api/schema.sql: full structure for every table, plus rows for the
# reference/config tables only. No customer data leaves the local DB.
set -euo pipefail

BACKEND="/home/aokwi/Desktop/New folder/afamfresh"
OUT="$BACKEND/schema.sql"
DUMP=/opt/lampp/bin/mysqldump
DB=$(/opt/lampp/bin/php -r "require '$BACKEND/includes/config.php'; echo DB_NAME;")
DBU=$(/opt/lampp/bin/php -r "require '$BACKEND/includes/config.php'; echo DB_USER;")
DBP=$(/opt/lampp/bin/php -r "require '$BACKEND/includes/config.php'; echo DB_PASS;")

# Tables whose CONTENTS are safe to version: catalogue, pricing, UI copy,
# feature flags. Everything else gets structure only.
SEED=(items category site_settings app_config delivery_pricing delivery_slots
      shipping banner gift_hampers gift_hamper_items Bulk_discount_rules
      Bulk_delivery_settings storage_pricing)

ARGS=(--host=localhost --user="$DBU" --default-character-set=utf8mb4
      --single-transaction --skip-add-locks --skip-comments
      --skip-set-charset --no-tablespaces)
[ -n "$DBP" ] && ARGS+=(--password="$DBP")

{
  echo "-- AfamFresh schema. Generated from the local MariaDB by"
  echo "-- scripts/export-schema.sh -- edit that, not this file."
  echo "--"
  echo "-- Structure for all tables; rows only for catalogue/pricing/UI-copy"
  echo "-- tables. No customer, order, rider or admin data is included."
  echo "--"
  echo "-- Normalised on export for Cloud SQL:"
  echo "--   DEFAULT curdate() -> DEFAULT (curdate())"
  echo "--                      (MariaDB allows a bare expression default;"
  echo "--                       MySQL 8.0 rejects it unless parenthesised."
  echo "--                       current_timestamp() is special-cased on"
  echo "--                       TIMESTAMP columns, so it is left alone.)"
  echo "--   MyISAM -> InnoDB   (Cloud SQL does not support MyISAM)"
  echo "--   latin1 -> utf8mb4  (13 legacy tables; verified lossless --"
  echo "--                       only 8 values were non-ASCII, all cp1252"
  echo "--                       typography that maps cleanly to Unicode)"
  echo ""
  echo "SET NAMES utf8mb4;"
  echo "SET FOREIGN_KEY_CHECKS=0;"
  echo ""
  echo "-- ============ STRUCTURE ============"
  "$DUMP" "${ARGS[@]}" --no-data "$DB"
  echo ""
  echo "-- ============ REFERENCE DATA ============"
  "$DUMP" "${ARGS[@]}" --no-create-info --complete-insert "$DB" "${SEED[@]}"
  echo ""
  echo "SET FOREIGN_KEY_CHECKS=1;"
} \
| sed -E 's/DEFAULT (curdate|curtime|current_date|current_time|uuid|rand)\(\)/DEFAULT (\1())/g;
          s/ENGINE=MyISAM/ENGINE=InnoDB/g;
          s/DEFAULT CHARSET=latin1( COLLATE=latin1_swedish_ci)?/DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci/g;
          s/CHARACTER SET latin1 COLLATE latin1_swedish_ci/CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci/g;
          s/CHARACTER SET latin1/CHARACTER SET utf8mb4/g;
          s/COLLATE=utf8mb4_general_ci/COLLATE=utf8mb4_unicode_ci/g;
          /^\/\*!999999/d' \
| grep -v '^$' > "$OUT"

echo "wrote $OUT"
wc -l "$OUT"
