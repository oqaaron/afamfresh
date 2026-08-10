<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

// =============================================================
// ACTION: update — the vendor fills in their business details.
// =============================================================
// The second stage of vendor onboarding. Approving a role request
// creates a vendors row from what a user account can supply: the
// person's own name as the business name, and a blank phone. This
// is where that becomes a real business record, before an admin
// verifies it and api/surplus-listings.php starts accepting
// listings.
//
// Unlike the read below, this identifies the vendor from the
// SESSION, never from a user_id in the request. Trusting the
// parameter would let anyone rewrite any vendor's details by
// changing a number in the URL.
// =============================================================
if (($_GET['action'] ?? '') === 'update') {
    if (!isset($_SESSION['user_id'])) {
        http_response_code(401);
        echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
        exit;
    }
    $me = (int)$_SESSION['user_id'];

    $input = json_decode(file_get_contents('php://input'), true) ?: [];
    $field = fn($k) => trim((string)($input[$k] ?? $_POST[$k] ?? ''));

    $businessName = $field('business_name');
    $phone        = $field('phone');
    $location     = $field('location');
    $marketStall  = $field('market_stall');
    $businessType = $field('business_type');

    // business_name and phone are the two the table declares NOT NULL, and
    // the two an admin needs in order to verify anything at all.
    if ($businessName === '' || $phone === '') {
        echo json_encode([
            'success' => false,
            'error'   => 'Business name and phone number are both required.',
        ]);
        exit;
    }

    // Whitelisted rather than passed through: business_type is an ENUM, and
    // MySQL answers an unknown value with a truncation warning and an empty
    // string, not an error.
    $allowedTypes = ['farmer', 'market_vendor', 'wholesaler', 'store', 'Fish Products'];
    if (!in_array($businessType, $allowedTypes, true)) {
        $businessType = 'market_vendor';
    }

    try {
        $upd = $dbh->prepare(
            "UPDATE vendors
                SET business_name = ?, business_type = ?, phone = ?,
                    location = ?, market_stall = ?
              WHERE user_id = ?"
        );
        $upd->execute([
            $businessName, $businessType, $phone,
            $location !== '' ? $location : null,
            $marketStall !== '' ? $marketStall : null,
            $me,
        ]);

        if ($upd->rowCount() === 0) {
            // Either no vendors row, or the details were already identical.
            // Distinguished so the app does not report success for an account
            // that was never approved.
            $has = $dbh->prepare("SELECT is_verified FROM vendors WHERE user_id = ?");
            $has->execute([$me]);
            $row = $has->fetch(PDO::FETCH_ASSOC);
            if (!$row) {
                echo json_encode([
                    'success' => false,
                    'error'   => 'This account has not been approved as a vendor yet.',
                ]);
                exit;
            }
        }

        $ver = $dbh->prepare("SELECT is_verified FROM vendors WHERE user_id = ?");
        $ver->execute([$me]);
        $isVerified = (int)$ver->fetchColumn() === 1;

        echo json_encode([
            'success'     => true,
            'is_verified' => $isVerified,
            'message'     => $isVerified
                ? 'Your business details have been updated.'
                : 'Thank you. Your details are with an administrator for verification — you can start listing products once that is done.',
        ]);
    } catch (PDOException $e) {
        error_log('vendor-profile update failed for user ' . $me . ': ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not save your details. Please try again.']);
    }
    exit;
}

// =============================================================
// READ: the vendor's own dashboard payload.
// =============================================================
// This used to take user_id from the query string and answer for
// whoever was named, with no authentication at all. Incrementing
// that number walked through every vendor on the platform and
// returned their revenue, commission rate, net earnings and
// reviews. It was reachable from the open internet.
//
// The signed-in user is now the default and the only permitted
// subject. The parameter is kept because the app sends it, but it
// must match the session -- an admin session may look at any
// vendor, because the admin pages legitimately do.
// =============================================================
$isAdmin = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;

if (!isset($_SESSION['user_id']) && !$isAdmin) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
    exit;
}

$sessionUserId = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

$requested = isset($_GET['user_id'])
    ? intval($_GET['user_id'])
    : (isset($_POST['user_id']) ? intval($_POST['user_id']) : 0);

// No parameter means "me", which is what every app caller wants anyway.
$user_id = $requested !== 0 ? $requested : $sessionUserId;

if ($user_id === 0) {
    echo json_encode(['error' => 'user_id is required']);
    exit;
}

if ($user_id !== $sessionUserId && !$isAdmin) {
    // Deliberately the same wording whether or not that vendor exists, so
    // this cannot be used to enumerate which accounts are vendors.
    http_response_code(403);
    echo json_encode(['success' => false, 'error' => 'Not your vendor account.']);
    exit;
}

try {
    // Fetch vendor profile
    $stmt = $dbh->prepare("
        SELECT v.*, u.email as user_email, u.fname, u.lname
        FROM vendors v
        JOIN users u ON v.user_id = u.id
        WHERE v.user_id = ?
    ");
    $stmt->execute([$user_id]);
    $vendor = $stmt->fetch(PDO::FETCH_ASSOC);
    
    if (!$vendor) {
        echo json_encode(['error' => 'Vendor not found']);
        exit;
    }
    
    // Fetch vendor products
    $productsStmt = $dbh->prepare("
        SELECT vp.*, i.name as product_name, i.category, i.image
        FROM vendor_products vp
        JOIN items i ON vp.product_id = i.id
        WHERE vp.vendor_id = ? AND vp.is_active = TRUE
    ");
    $productsStmt->execute([$vendor['id']]);
    $products = $productsStmt->fetchAll(PDO::FETCH_ASSOC);
    
    // Fetch vendor earnings summary
    $earningsStmt = $dbh->prepare("
        SELECT 
            COUNT(*) as total_orders,
            SUM(order_amount) as total_revenue,
            SUM(commission_amount) as total_commission,
            SUM(net_earnings) as total_net_earnings,
            SUM(CASE WHEN is_paid = TRUE THEN net_earnings ELSE 0 END) as paid_earnings,
            SUM(CASE WHEN is_paid = FALSE THEN net_earnings ELSE 0 END) as pending_earnings
        FROM vendor_earnings
        WHERE vendor_id = ?
    ");
    $earningsStmt->execute([$vendor['id']]);
    $earnings = $earningsStmt->fetch(PDO::FETCH_ASSOC);
    
    // Fetch recent reviews
    $reviewsStmt = $dbh->prepare("
        SELECT vr.*, u.fname as reviewer_name
        FROM vendor_reviews vr
        JOIN users u ON vr.user_id = u.id
        WHERE vr.vendor_id = ? AND vr.is_approved = TRUE
        ORDER BY vr.created_at DESC
        LIMIT 5
    ");
    $reviewsStmt->execute([$vendor['id']]);
    $reviews = $reviewsStmt->fetchAll(PDO::FETCH_ASSOC);
    
    echo json_encode([
        'success' => true,
        'vendor' => $vendor,
        'products' => $products,
        'earnings' => $earnings,
        'reviews' => $reviews
    ]);
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
