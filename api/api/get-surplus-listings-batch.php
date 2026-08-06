<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

$input = json_decode(file_get_contents('php://input'), true);
$listingIds = $input['listing_ids'] ?? [];

if (empty($listingIds)) {
    echo json_encode(['success' => true, 'listings' => []]);
    exit;
}

$placeholders = implode(',', array_fill(0, count($listingIds), '?'));
$stmt = $dbh->prepare("
    SELECT sl.id, sl.discounted_price as price, sl.weight_per_unit_kg, sl.pickup_only,
           i.name as product_name, i.image
    FROM surplus_listings sl
    JOIN items i ON sl.product_id = i.id
    WHERE sl.id IN ($placeholders) AND sl.status = 'active'
");
$stmt->execute($listingIds);
$listings = $stmt->fetchAll(PDO::FETCH_ASSOC);

echo json_encode(['success' => true, 'listings' => $listings]);
?>