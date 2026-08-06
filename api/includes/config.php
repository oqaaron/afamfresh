<?php
// =============================================================
// AfamFresh - legacy config shim
// =============================================================
// This file used to be a second, older copy of the main config: the
// same constant names, its own PDO connection, and its own literal
// copies of the Pesapal, Brevo and Twilio credentials. Two copies of
// a credential means the one you don't remember about is the one that
// leaks, and defining the same constants twice would have emitted
// "already defined" warnings had both ever loaded in one request.
//
// The real configuration is admin/includes/config.php, which reads
// every credential from env.yaml via includes/env.php. This shim
// stays only so that anything still pointing here keeps working.
//
// Nothing in the app requires this file today — admin/*.php resolve
// 'includes/config.php' relative to admin/, so they get the canonical
// one. Safe to delete once you've confirmed the same.
// =============================================================

require_once __DIR__ . '/../admin/includes/config.php';
