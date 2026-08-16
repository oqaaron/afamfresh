<?php
session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
requireAdminPermission('products.manage_pricing');

// POST only. This used to delete on a GET, which meant any page an admin
// happened to visit could destroy a product with nothing more than
// <img src="http://.../admin/delete-product.php?id=42">. products.php now
// submits a form here instead of linking.
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: products.php');
    exit;
}

// POST alone isn't enough -- a hidden auto-submitting form on any site an
// admin's browser visits can also POST here using their session cookie.
// This file is unreferenced now (products.php handles delete inline with
// its own verifyCsrf() call) but stays reachable by direct URL, so it needs
// the same token check every other state-changing admin page has.
verifyCsrf();

$id = intval($_POST['id'] ?? 0);
if ($id) {
    $stmt = $dbh->prepare("DELETE FROM items WHERE id = ?");
    $stmt->execute([$id]);
    logAdminAction($dbh, 'product.deleted', 'product', (string)$id, 'Deleted product');
}

header('Location: products.php?deleted=1');
exit;