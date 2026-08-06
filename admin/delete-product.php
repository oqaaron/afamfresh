<?php
session_start();
require_once 'includes/config.php';

// === true, not a bare isset(): isset() is satisfied by a value of
// literal false, which would let a logged-out session through.
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

// POST only. This used to delete on a GET, which meant any page an admin
// happened to visit could destroy a product with nothing more than
// <img src="http://.../admin/delete-product.php?id=42">. products.php now
// submits a form here instead of linking.
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: products.php');
    exit;
}

$id = intval($_POST['id'] ?? 0);
if ($id) {
    $stmt = $dbh->prepare("DELETE FROM items WHERE id = ?");
    $stmt->execute([$id]);
}

header('Location: products.php?deleted=1');
exit;