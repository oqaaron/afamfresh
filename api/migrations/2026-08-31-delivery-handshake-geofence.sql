-- Migration: 2026-08-31-delivery-handshake-geofence.sql
-- Purpose: add customer handoff metadata and a rider verification code to orders.

ALTER TABLE orders
    ADD COLUMN landmark_notes VARCHAR(255) NULL AFTER delivery_address,
    ADD COLUMN delivery_otp VARCHAR(4) NULL AFTER landmark_notes,
    ADD COLUMN dropoff_latitude DECIMAL(10, 8) NULL AFTER delivery_otp,
    ADD COLUMN dropoff_longitude DECIMAL(11, 8) NULL AFTER dropoff_latitude;

ALTER TABLE riders
    ADD COLUMN vehicle_plate VARCHAR(32) NULL AFTER vehicle_type;

-- Generate a 4-digit OTP for any existing order that still needs verification.
UPDATE orders
SET delivery_otp = LPAD(FLOOR(RAND() * 10000), 4, '0')
WHERE delivery_otp IS NULL;
