<?php
require_once __DIR__ . '/../session_bootstrap.php';
session_start();
require_once '../admin/includes/config.php';
session_destroy();
header('Location: login.php');
exit;
