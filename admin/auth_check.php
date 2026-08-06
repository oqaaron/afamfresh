<?php
// admin/includes/auth_check.php

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

function isAdminLoggedIn() {
    return isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
}

function requireAdminLogin() {
    if (!isAdminLoggedIn()) {
        http_response_code(401);
        echo json_encode(['success' => false, 'error' => 'Unauthorized. Please log in.']);
        exit;
    }
}

function requireAdminLoginWeb() {
    if (!isAdminLoggedIn()) {
        header('Location: login.php');
        exit;
    }
}
?>