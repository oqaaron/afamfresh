<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/bulk_listing_rules.php';

$method = $_SERVER['REQUEST_METHOD'];

try {
    if ($method === 'GET') {
        // Fetch approved Bulk listings (public)
        $vendor_id = isset($_GET['vendor_id']) ? intval($_GET['vendor_id']) : 0;
        $status = isset($_GET['status']) ? trim($_GET['status']) : 'approved'; // default to approved
        $listing_type = isset($_GET['listing_type']) ? trim($_GET['listing_type']) : '';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE sl.status = ? AND sl.remaining_quantity > 0 AND sl.expiry_date > NOW()";
        $params = [$status];
        
        if ($vendor_id > 0) {
            $whereClause .= " AND sl.vendor_id = ?";
            $params[] = $vendor_id;
        }
        if (!empty($listing_type)) {
            $whereClause .= " AND sl.listing_type = ?";
            $params[] = $listing_type;
        }
        
        $stmt = $dbh->prepare("
            SELECT sl.*, v.business_name, v.location as vendor_location,
                   i.name as product_name, i.category, i.image,
                   u.fname as vendor_fname, u.lname as vendor_lname
            FROM Bulk_listings sl
            JOIN vendors v ON sl.vendor_id = v.id
            JOIN items i ON sl.product_id = i.id
            JOIN users u ON v.user_id = u.id
            $whereClause
            ORDER BY sl.discount_percent DESC, sl.created_at ASC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $listings = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'listings' => $listings]);
        
    } elseif ($method === 'POST') {
        // Create new Bulk listing (vendor submits, sets status = 'pending')
        $input = json_decode(file_get_contents('php://input'), true);
        
        // Whoever is signed in is the vendor listing this. Taken from the body
        // before, so the is_verified check below could be satisfied by naming
        // a verified vendor's user_id and listing stock in their name.
        require_once __DIR__ . '/../includes/api_auth.php';
        $user_id = requireOwnUserId($input['user_id'] ?? 0);
        $product_id = intval($input['product_id'] ?? 0);
        $original_price = floatval($input['original_price'] ?? 0);
        $discount_percent = floatval($input['discount_percent'] ?? 0);
        $Bulk_quantity = floatval($input['Bulk_quantity'] ?? 0);
        $expiry_date = trim($input['expiry_date'] ?? '');
        $listing_type = trim($input['listing_type'] ?? 'goodie_bag');
        $description = trim($input['description'] ?? '');
        $condition_rating = trim($input['condition_rating'] ?? 'good');
        // Cast to int, not bool.
        //
        // PDO binds a PHP false as an EMPTY STRING when parameters are passed
        // as an array to execute(), and MySQL in strict mode refuses '' for a
        // tinyint: "Incorrect integer value: '' for column 'pickup_only'". So
        // every listing with pickup_only unticked failed, which is most of
        // them, while a ticked one bound '1' and went through.
        //
        // is_weight_based has the same fault and would have failed for any
        // unit that is not kilograms.
        $pickup_only = !empty($input['pickup_only']) ? 1 : 0;
        $weight_per_unit_kg = floatval($input['weight_per_unit_kg'] ?? 1.00);
        $is_weight_based = isset($input['is_weight_based'])
            ? (!empty($input['is_weight_based']) ? 1 : 0)
            : 1;
        
        // Validate
        if ($user_id === 0 || $product_id === 0 || $original_price === 0 || $discount_percent === 0 || $Bulk_quantity === 0 || empty($expiry_date)) {
            echo json_encode(['error' => 'Missing required fields']);
            exit;
        }
        if (!isValidSurplusDiscount($discount_percent)) {
            echo json_encode(['error' => surplusDiscountRangeMessage()]);
            exit;
        }
        
        // Get vendor_id from user_id
        $vendorStmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ? AND is_verified = TRUE");
        $vendorStmt->execute([$user_id]);
        $vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);
        if (!$vendor) {
            echo json_encode(['error' => 'Verified vendor not found']);
            exit;
        }
        $vendor_id = $vendor['id'];

        // The product must be this vendor's own, and approved.
        //
        // product_id was previously taken on trust and only the foreign key
        // checked it — so any id in `items` was listable, including another
        // vendor's product and one still awaiting approval. That would have
        // let a vendor sell Bulk "of" a product they do not own, and let
        // anyone bypass the approval this whole flow exists to enforce.
        $ownProduct = $dbh->prepare(
            "SELECT status FROM items WHERE id = ? AND vendor_id = ?"
        );
        $ownProduct->execute([$product_id, $vendor_id]);
        $productStatus = $ownProduct->fetchColumn();

        if ($productStatus === false) {
            echo json_encode(['error' => 'That product is not one of yours.']);
            exit;
        }
        if ($productStatus !== 'approved') {
            echo json_encode([
                'error' => 'That product is still waiting for approval, so it cannot be listed yet.',
            ]);
            exit;
        }

        $discounted_price = $original_price * (1 - ($discount_percent / 100));
        
        $stmt = $dbh->prepare("
            INSERT INTO Bulk_listings 
            (vendor_id, product_id, original_price, discount_percent, discounted_price, 
             Bulk_quantity, remaining_quantity, expiry_date, listing_type, description, 
             condition_rating, pickup_only, weight_per_unit_kg, is_weight_based, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
        ");
        $result = $stmt->execute([
            $vendor_id, $product_id, $original_price, $discount_percent, $discounted_price,
            $Bulk_quantity, $Bulk_quantity, $expiry_date, $listing_type, $description,
            $condition_rating, $pickup_only, $weight_per_unit_kg, $is_weight_based
        ]);
        
        if ($result) {
            $listing_id = $dbh->lastInsertId();
            $fetchStmt = $dbh->prepare("SELECT * FROM Bulk_listings WHERE id = ?");
            $fetchStmt->execute([$listing_id]);
            $listing = $fetchStmt->fetch(PDO::FETCH_ASSOC);
            echo json_encode([
                'success' => true,
                'message' => 'Bulk listing submitted for approval',
                'listing' => $listing
            ]);
        } else {
            echo json_encode(['error' => 'Failed to create Bulk listing']);
        }
        
    } elseif ($method === 'PUT') {
        // Update Bulk listing (status, quantity, admin notes).
        //
        // Had no auth check at all: listing_id, status, remaining_quantity
        // and admin_notes came straight from the body and were written
        // through unconditionally, so anyone could approve their own
        // rejected listing, inflate stock, or edit another vendor's
        // listing by guessing an id. The vendor app only ever sends
        // remaining_quantity (see VendorRepository.updateListingQuantity);
        // status/admin_notes are admin-only, since status='approved' is
        // the entire point of the review workflow admin/Bulk-listings.php
        // enforces.
        $isAdminSession = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
        $sessionUserId  = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

        $input = json_decode(file_get_contents('php://input'), true);
        $listing_id = intval($input['listing_id'] ?? 0);
        $status = isset($input['status']) ? trim($input['status']) : null;
        $remaining_quantity = isset($input['remaining_quantity']) ? floatval($input['remaining_quantity']) : null;
        $admin_notes = isset($input['admin_notes']) ? trim($input['admin_notes']) : null;

        if ($listing_id === 0) {
            echo json_encode(['error' => 'listing_id is required']);
            exit;
        }

        $owner = $dbh->prepare(
            "SELECT v.user_id FROM Bulk_listings sl JOIN vendors v ON v.id = sl.vendor_id WHERE sl.id = ?"
        );
        $owner->execute([$listing_id]);
        $ownerUserId = $owner->fetchColumn();
        if ($ownerUserId === false) {
            echo json_encode(['error' => 'No such listing']);
            exit;
        }
        if (!$isAdminSession) {
            if ($sessionUserId === 0) {
                http_response_code(401);
                echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
                exit;
            }
            if ((int)$ownerUserId !== $sessionUserId) {
                http_response_code(403);
                echo json_encode(['success' => false, 'error' => 'That listing is not yours.']);
                exit;
            }
            if ($status !== null || $admin_notes !== null) {
                http_response_code(403);
                echo json_encode(['success' => false, 'error' => 'Only an admin can change status or notes.']);
                exit;
            }
        }

        $updateFields = [];
        $params = [];
        if ($status !== null) {
            $updateFields[] = "status = ?";
            $params[] = $status;
        }
        if ($remaining_quantity !== null) {
            $updateFields[] = "remaining_quantity = ?";
            $params[] = $remaining_quantity;
        }
        if ($admin_notes !== null) {
            $updateFields[] = "admin_notes = ?";
            $params[] = $admin_notes;
        }
        if (empty($updateFields)) {
            echo json_encode(['error' => 'No fields to update']);
            exit;
        }
        $updateFields[] = "updated_at = NOW()";
        $params[] = $listing_id;
        $sql = "UPDATE Bulk_listings SET " . implode(', ', $updateFields) . " WHERE id = ?";
        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);
        echo json_encode(['success' => true, 'message' => 'Listing updated successfully']);
        
    } elseif ($method === 'DELETE') {
        // Cancel Bulk listing (soft delete = set status to 'cancelled').
        //
        // Had no auth check either: any listing_id cancelled any vendor's
        // live listing.
        $isAdminSession = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
        $sessionUserId  = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

        $listing_id = intval($_GET['listing_id'] ?? 0);
        if ($listing_id === 0) {
            echo json_encode(['error' => 'listing_id is required']);
            exit;
        }

        $owner = $dbh->prepare(
            "SELECT v.user_id FROM Bulk_listings sl JOIN vendors v ON v.id = sl.vendor_id WHERE sl.id = ?"
        );
        $owner->execute([$listing_id]);
        $ownerUserId = $owner->fetchColumn();
        if ($ownerUserId === false) {
            echo json_encode(['error' => 'No such listing']);
            exit;
        }
        if (!$isAdminSession) {
            if ($sessionUserId === 0) {
                http_response_code(401);
                echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
                exit;
            }
            if ((int)$ownerUserId !== $sessionUserId) {
                http_response_code(403);
                echo json_encode(['success' => false, 'error' => 'That listing is not yours.']);
                exit;
            }
        }

        $stmt = $dbh->prepare("UPDATE Bulk_listings SET status = 'cancelled', updated_at = NOW() WHERE id = ?");
        $stmt->execute([$listing_id]);
        echo json_encode(['success' => true, 'message' => 'Listing cancelled successfully']);

    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
} catch (PDOException $e) {
    error_log('Bulk-listings.php error: ' . $e->getMessage());
    echo json_encode(['error' => 'A database error occurred. Please try again.']);
}
?>