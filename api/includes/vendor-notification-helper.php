<?php
/**
 * vendor-notification-helper.php
 * Helper functions for vendor notifications
 */

require_once __DIR__ . '/notifications.php';

/**
 * Send new order notification to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $order_id - Order ID
 * @param array $order_items - Products in the order
 * @param float $order_total - Total amount for this vendor's products
 * @return bool - Success status
 */
function notifyVendorNewOrder($vendor_user_id, $order_id, $order_items, $order_total) {
    $item_count = count($order_items);
    $item_names = array_slice(array_column($order_items, 'product_name'), 0, 3);
    $item_list = implode(', ', $item_names);
    
    if ($item_count > 3) {
        $item_list .= " and " . ($item_count - 3) . " more";
    }
    
    $title = "📦 New Order #{$order_id}";
    $message = "You have received a new order! {$item_count} item(s): {$item_list}. Total: UGX " . number_format($order_total);
    $link = "vendor-orders.php?view=order&id={$order_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'order', $link);
}

/**
 * Send order status update notification to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $order_id - Order ID
 * @param string $status - New status
 * @return bool - Success status
 */
function notifyVendorOrderStatusUpdate($vendor_user_id, $order_id, $status) {
    $status_labels = [
        'paid' => 'Paid 💰',
        'processing' => 'Being Processed 🔧',
        'shipped' => 'Shipped 🚚',
        'completed' => 'Completed ✅',
        'cancelled' => 'Cancelled ❌'
    ];
    
    $status_display = $status_labels[strtolower($status)] ?? ucfirst($status);
    
    $title = "📋 Order #{$order_id} Status Update";
    $message = "Order #{$order_id} has been marked as {$status_display}.";
    $link = "vendor-orders.php?view=order&id={$order_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'order', $link);
}

/**
 * Send low stock alert to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $vendor_id - Vendor's ID
 * @param int $product_id - Product ID
 * @param string $product_name - Product name
 * @param int $current_stock - Current stock quantity
 * @param int $threshold - Low stock threshold
 * @return bool - Success status
 */
function notifyVendorLowStock($vendor_user_id, $vendor_id, $product_id, $product_name, $current_stock, $threshold) {
    global $dbh;
    
    // Check if we already sent a notification in the last 24 hours for this product
    $checkStmt = $dbh->prepare("
        SELECT created_at FROM user_notifications 
        WHERE user_id = ? 
        AND title LIKE ? 
        AND created_at > DATE_SUB(NOW(), INTERVAL 24 HOUR)
        LIMIT 1
    ");
    $checkStmt->execute([$vendor_user_id, "%Low Stock: %{$product_name}%"]);
    
    if ($checkStmt->fetch()) {
        return false;
    }
    
    $title = "⚠️ Low Stock Alert: {$product_name}";
    $message = "Your product \"{$product_name}\" is running low! Only {$current_stock} units remaining (threshold: {$threshold}). Please restock soon.";
    $link = "vendor-products.php?action=edit&id={$product_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'system', $link);
}

/**
 * Send bulk low stock report to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param array $low_stock_products - Array of products with low stock
 * @return bool - Success status
 */
function notifyVendorLowStockBulk($vendor_user_id, $low_stock_products) {
    if (empty($low_stock_products)) {
        return false;
    }
    
    $product_count = count($low_stock_products);
    $product_list = [];
    
    foreach (array_slice($low_stock_products, 0, 5) as $product) {
        $product_list[] = "• {$product['name']} (Stock: {$product['stock_quantity']})";
    }
    
    $product_list_str = implode("\n", $product_list);
    
    if ($product_count > 5) {
        $product_list_str .= "\n• ... and " . ($product_count - 5) . " more products";
    }
    
    $title = "📊 Low Stock Summary";
    $message = "You have {$product_count} product(s) running low on stock:\n\n{$product_list_str}\n\nPlease restock to avoid lost sales.";
    $link = "vendor-products.php?filter=low_stock";
    
    return addNotification($vendor_user_id, $title, $message, 'system', $link);
}

/**
 * Send payment confirmation notification to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $order_id - Order ID
 * @param float $amount - Payment amount
 * @return bool - Success status
 */
function notifyVendorPaymentConfirmed($vendor_user_id, $order_id, $amount) {
    $title = "💰 Payment Confirmed - Order #{$order_id}";
    $message = "Payment of UGX " . number_format($amount) . " has been confirmed for order #{$order_id}.";
    $link = "vendor-orders.php?view=order&id={$order_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'order', $link);
}

/**
 * Send product approved notification to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $product_id - Product ID
 * @param string $product_name - Product name
 * @return bool - Success status
 */
function notifyVendorProductApproved($vendor_user_id, $product_id, $product_name) {
    $title = "✅ Product Approved: {$product_name}";
    $message = "Your product \"{$product_name}\" has been approved and is now live on AfamFresh!";
    $link = "vendor-products.php?action=edit&id={$product_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'system', $link);
}

/**
 * Send product rejected notification to vendor
 * @param int $vendor_user_id - Vendor's user ID
 * @param int $product_id - Product ID
 * @param string $product_name - Product name
 * @param string $reason - Rejection reason
 * @return bool - Success status
 */
function notifyVendorProductRejected($vendor_user_id, $product_id, $product_name, $reason) {
    $title = "❌ Product Rejected: {$product_name}";
    $message = "Your product \"{$product_name}\" was not approved. Reason: {$reason}. Please edit and resubmit.";
    $link = "vendor-products.php?action=edit&id={$product_id}";
    
    return addNotification($vendor_user_id, $title, $message, 'system', $link);
}

/**
 * Check all products for low stock and send alerts
 * @return array - Summary of alerts sent
 */
function checkAllVendorLowStockAlerts() {
    global $dbh;
    
    $summary = ['vendors_notified' => 0, 'alerts_sent' => 0, 'errors' => []];
    
    try {
        $stmt = $dbh->prepare("
            SELECT v.id as vendor_id, v.user_id, v.business_name, v.low_stock_threshold,
                   vp.id as vendor_product_id, vp.product_id as item_id, vp.stock_quantity,
                   i.name as product_name
            FROM vendors v
            JOIN vendor_products vp ON v.id = vp.vendor_id
            JOIN items i ON vp.product_id = i.id
            WHERE vp.is_active = TRUE 
            AND vp.stock_quantity <= v.low_stock_threshold
            ORDER BY v.id
        ");
        $stmt->execute();
        $low_stock_items = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        $vendor_low_stock = [];
        foreach ($low_stock_items as $item) {
            $vendor_low_stock[$item['vendor_id']]['vendor_info'] = [
                'user_id' => $item['user_id'],
                'business_name' => $item['business_name'],
                'threshold' => $item['low_stock_threshold']
            ];
            $vendor_low_stock[$item['vendor_id']]['products'][] = $item;
        }
        
        foreach ($vendor_low_stock as $vendor_id => $vendor_data) {
            $vendor_info = $vendor_data['vendor_info'];
            $products = $vendor_data['products'];
            
            if (count($products) == 1) {
                $product = $products[0];
                notifyVendorLowStock(
                    $vendor_info['user_id'],
                    $vendor_id,
                    $product['item_id'],
                    $product['product_name'],
                    $product['stock_quantity'],
                    $vendor_info['threshold']
                );
                $summary['alerts_sent']++;
            } else {
                $product_list = [];
                foreach ($products as $p) {
                    $product_list[] = [
                        'name' => $p['product_name'],
                        'stock_quantity' => $p['stock_quantity']
                    ];
                }
                notifyVendorLowStockBulk($vendor_info['user_id'], $product_list);
                $summary['alerts_sent']++;
            }
            $summary['vendors_notified']++;
        }
        
    } catch (Exception $e) {
        $summary['errors'][] = $e->getMessage();
        error_log("Low stock check error: " . $e->getMessage());
    }
    
    return $summary;
}

/**
 * Tell a vendor an admin has verified them.
 *
 * The end of the onboarding chain, and the only point in it where anything
 * reaches the vendor. Approval, submitting details and verification all happen
 * elsewhere; without this the vendor learns they can trade by reopening the app
 * and noticing that a card has disappeared.
 *
 * Writes through addNotification to `user_notifications`, which is what
 * api/notifications.php serves and the app actually reads. Note the
 * `vendor_notifications` table and api/vendor-notifications.php are a separate,
 * parallel system that nothing in the three apps calls.
 *
 * @param int $vendor_user_id - the vendor's users.id, NOT vendors.id
 * @return bool
 */
function notifyVendorVerified($vendor_user_id) {
    // Email as well as push: this is the end of an onboarding wait that may
    // have run for days, and it is the one message a vendor is actively
    // waiting on. Reaching them without the app being open is the point.
    return addNotification(
        $vendor_user_id,
        '✅ Your vendor account is verified',
        'An administrator has verified your business details. You can now list '
            . 'products on AfamFresh.',
        'system',
        null,
        ['push', 'email']
    );
}

/**
 * Get vendor ID from user ID
 * @param int $user_id - User ID
 * @return int|null - Vendor ID or null
 */
function getVendorIdFromUserId($user_id) {
    global $dbh;
    $stmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
    $stmt->execute([$user_id]);
    $result = $stmt->fetch();
    return $result ? $result['id'] : null;
}

/**
 * Get vendor low stock threshold
 * @param int $vendor_id - Vendor ID
 * @return int - Threshold value
 */
function getVendorLowStockThreshold($vendor_id) {
    global $dbh;
    $stmt = $dbh->prepare("SELECT low_stock_threshold FROM vendors WHERE id = ?");
    $stmt->execute([$vendor_id]);
    $result = $stmt->fetch();
    return $result ? (int)$result['low_stock_threshold'] : 5;
}
?>