<?php
// =============================================================
// api/addresses.php — the customer's saved delivery addresses.
// =============================================================
// Replaces on-device SharedPreferences storage
// (app/.../repository/AddressRepository.kt's LocalAddressRepository) so
// addresses sync across a customer's devices and survive a reinstall.
// Every row belongs to the signed-in session user; there is no id-based
// ownership bypass to guard against separately since every query is scoped
// by `user_id = ?` from the session, the same pattern notifications.php
// uses.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/account_type.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = (int)$_SESSION['user_id'];
// Saved addresses are a customer-checkout concept — no legitimate rider or
// vendor flow reaches this endpoint (unlike notifications.php, which the
// rider/vendor apps genuinely share with the customer app for their own
// notification feed).
requireAccountType($dbh, $user_id, 'customer');
$action = $_GET['action'] ?? '';

/** Row -> the exact shape Address.kt's @SerializedName fields expect. */
function formatAddress(array $row): array {
    return [
        'id' => (string)$row['id'],
        'label' => $row['label'],
        'recipient_name' => $row['recipient_name'],
        'phone' => $row['phone'],
        'area' => $row['area'],
        'address_line' => $row['address_line'],
        'is_default' => (bool)$row['is_default'],
        'lat' => $row['latitude'] !== null ? (float)$row['latitude'] : null,
        'lng' => $row['longitude'] !== null ? (float)$row['longitude'] : null,
    ];
}

if ($action === 'list') {
    $stmt = $dbh->prepare(
        "SELECT * FROM customer_addresses WHERE user_id = ? ORDER BY is_default DESC, id DESC"
    );
    $stmt->execute([$user_id]);
    $addresses = array_map('formatAddress', $stmt->fetchAll());
    echo json_encode(['success' => true, 'addresses' => $addresses]);

} elseif ($action === 'create') {
    $input = json_decode(file_get_contents('php://input'), true) ?: [];

    $label = trim((string)($input['label'] ?? ''));
    $recipientName = trim((string)($input['recipient_name'] ?? ''));
    $phone = trim((string)($input['phone'] ?? ''));
    $area = trim((string)($input['area'] ?? ''));
    $addressLine = trim((string)($input['address_line'] ?? ''));
    $isDefault = !empty($input['is_default']);
    $lat = isset($input['lat']) && $input['lat'] !== null ? (float)$input['lat'] : null;
    $lng = isset($input['lng']) && $input['lng'] !== null ? (float)$input['lng'] : null;

    if ($area === '' || $addressLine === '') {
        echo json_encode(['success' => false, 'error' => 'area and address_line are required']);
        exit;
    }

    try {
        $dbh->beginTransaction();

        // The first address a customer saves becomes the default automatically
        // — otherwise someone with exactly one address would have none.
        $existing = $dbh->prepare("SELECT COUNT(*) FROM customer_addresses WHERE user_id = ?");
        $existing->execute([$user_id]);
        $isFirst = ((int)$existing->fetchColumn()) === 0;
        $shouldBeDefault = $isDefault || $isFirst;

        if ($shouldBeDefault) {
            $dbh->prepare("UPDATE customer_addresses SET is_default = 0 WHERE user_id = ?")
                ->execute([$user_id]);
        }

        $stmt = $dbh->prepare(
            "INSERT INTO customer_addresses
                (user_id, label, recipient_name, phone, area, address_line, latitude, longitude, is_default)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );
        $stmt->execute([
            $user_id, $label, $recipientName, $phone, $area, $addressLine, $lat, $lng,
            $shouldBeDefault ? 1 : 0,
        ]);
        $newId = (int)$dbh->lastInsertId();

        $dbh->commit();

        $row = $dbh->prepare("SELECT * FROM customer_addresses WHERE id = ?");
        $row->execute([$newId]);
        echo json_encode(['success' => true, 'address' => formatAddress($row->fetch())]);
    } catch (PDOException $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('addresses.php create failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not save address.']);
    }

} elseif ($action === 'update') {
    $input = json_decode(file_get_contents('php://input'), true) ?: [];

    $id = (int)($input['id'] ?? 0);
    $label = trim((string)($input['label'] ?? ''));
    $recipientName = trim((string)($input['recipient_name'] ?? ''));
    $phone = trim((string)($input['phone'] ?? ''));
    $area = trim((string)($input['area'] ?? ''));
    $addressLine = trim((string)($input['address_line'] ?? ''));
    $isDefault = !empty($input['is_default']);
    $lat = isset($input['lat']) && $input['lat'] !== null ? (float)$input['lat'] : null;
    $lng = isset($input['lng']) && $input['lng'] !== null ? (float)$input['lng'] : null;

    if ($id === 0 || $area === '' || $addressLine === '') {
        echo json_encode(['success' => false, 'error' => 'id, area and address_line are required']);
        exit;
    }

    try {
        $dbh->beginTransaction();

        // Verified BEFORE anything else touches this user's rows. Clearing
        // every default first and only then attempting a targeted update
        // meant a foreign or stale id left the caller with zero defaults —
        // the clear always ran, the set never did.
        $owns = $dbh->prepare("SELECT 1 FROM customer_addresses WHERE id = ? AND user_id = ?");
        $owns->execute([$id, $user_id]);
        if (!$owns->fetchColumn()) {
            $dbh->rollBack();
            http_response_code(404);
            echo json_encode(['success' => false, 'error' => 'Address not found']);
            exit;
        }

        if ($isDefault) {
            $dbh->prepare("UPDATE customer_addresses SET is_default = 0 WHERE user_id = ?")
                ->execute([$user_id]);
        }

        $stmt = $dbh->prepare(
            "UPDATE customer_addresses
                SET label = ?, recipient_name = ?, phone = ?, area = ?, address_line = ?,
                    latitude = ?, longitude = ?, is_default = ?
              WHERE id = ? AND user_id = ?"
        );
        $stmt->execute([
            $label, $recipientName, $phone, $area, $addressLine, $lat, $lng,
            $isDefault ? 1 : 0, $id, $user_id,
        ]);

        $dbh->commit();
        echo json_encode(['success' => true]);
    } catch (PDOException $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('addresses.php update failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not update address.']);
    }

} elseif ($action === 'delete') {
    $id = intval($_POST['id'] ?? 0);
    if ($id === 0) {
        echo json_encode(['success' => false, 'error' => 'id is required']);
        exit;
    }

    try {
        $dbh->beginTransaction();

        $wasDefault = $dbh->prepare("SELECT is_default FROM customer_addresses WHERE id = ? AND user_id = ?");
        $wasDefault->execute([$id, $user_id]);
        $isDefaultRow = (bool)$wasDefault->fetchColumn();

        $dbh->prepare("DELETE FROM customer_addresses WHERE id = ? AND user_id = ?")
            ->execute([$id, $user_id]);

        // Deleting the default promotes another address, so the customer is
        // never left with saved addresses but nothing marked default —
        // matches LocalAddressRepository's exact rule.
        if ($isDefaultRow) {
            $dbh->prepare(
                "UPDATE customer_addresses SET is_default = 1
                  WHERE user_id = ? ORDER BY id LIMIT 1"
            )->execute([$user_id]);
        }

        $dbh->commit();
        echo json_encode(['success' => true]);
    } catch (PDOException $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('addresses.php delete failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not delete address.']);
    }

} elseif ($action === 'set_default') {
    $id = intval($_POST['id'] ?? 0);
    if ($id === 0) {
        echo json_encode(['success' => false, 'error' => 'id is required']);
        exit;
    }

    try {
        $dbh->beginTransaction();

        // Same fix as 'update': verify ownership before clearing anything,
        // so a foreign/nonexistent id can't wipe the caller's real default
        // and then fail to set a new one.
        $owns = $dbh->prepare("SELECT 1 FROM customer_addresses WHERE id = ? AND user_id = ?");
        $owns->execute([$id, $user_id]);
        if (!$owns->fetchColumn()) {
            $dbh->rollBack();
            http_response_code(404);
            echo json_encode(['success' => false, 'error' => 'Address not found']);
            exit;
        }

        $dbh->prepare("UPDATE customer_addresses SET is_default = 0 WHERE user_id = ?")
            ->execute([$user_id]);
        $dbh->prepare("UPDATE customer_addresses SET is_default = 1 WHERE id = ? AND user_id = ?")
            ->execute([$id, $user_id]);
        $dbh->commit();
        echo json_encode(['success' => true]);
    } catch (PDOException $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('addresses.php set_default failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not set default address.']);
    }

} else {
    echo json_encode(['success' => false, 'error' => 'Invalid action']);
}
