<?php
// =============================================================
// api/roles.php — role requests for the Rider and Vendor apps
//
// Actions: status | request | my_requests
//
// The Rider and Vendor apps are separate installs, but installing
// one grants nothing: a user registers, files a request here, and
// waits for an admin to approve it in admin/role-requests.php.
//
// This is a THIN wrapper. The whole workflow already lives in
// includes/roles.php (submitRoleRequest, canRequestRole,
// hasPendingRoleRequest, getUserRoleRequests) and had simply never
// been exposed over HTTP. Nothing is reimplemented here.
//
// NOTE: deliberately no ini_set('display_errors', 1), matching
// profile.php and rider.php. A PHP notice printed before the JSON
// prepends HTML to the body and Gson then fails to parse the ENTIRE
// response — the symptom is a working endpoint the app calls broken.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/roles.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action  = $_GET['action'] ?? '';

$jsonBody = json_decode(file_get_contents('php://input'), true);
if (!is_array($jsonBody)) { $jsonBody = []; }

function param($key, $default = null) {
    global $jsonBody;
    if (array_key_exists($key, $jsonBody)) return $jsonBody[$key];
    if (array_key_exists($key, $_POST))    return $_POST[$key];
    if (array_key_exists($key, $_GET))     return $_GET[$key];
    return $default;
}

function fail($message) {
    echo json_encode(['success' => false, 'error' => $message]);
    exit;
}

/** Only roles an app can be built for. 'user' is the baseline nobody requests. */
$REQUESTABLE = ['rider', 'vendor', 'wholesaler'];

// =============================================================
// ACTION: STATUS
//
// What the app shows on launch: may this account use this app yet?
// One call answers approved / pending / rejected / never asked, so
// the app does not have to infer state from a failed workspace call.
// =============================================================
if ($action === 'status') {
    $role = trim((string)param('role', ''));
    if (!in_array($role, $REQUESTABLE, true)) {
        fail('Unknown role.');
    }

    // userHasRole() already filters on status = 'active'.
    $approved = userHasRole($user_id, $role);

    $state = 'none';
    if ($approved) {
        $state = 'approved';
    } elseif (hasPendingRoleRequest($user_id, $role)) {
        $state = 'pending';
    } else {
        // A rejection is only visible in the request history.
        foreach (getUserRoleRequests($user_id) as $r) {
            if ($r['requested_role'] === $role && $r['status'] === 'rejected') {
                $state = 'rejected';
                break;
            }
        }
    }

    // canRequestRole() returns true or an explanatory sentence; the sentence is
    // what the app shows when the button is unavailable (most usefully "you can
    // only have one additional role").
    $can = canRequestRole($user_id, $role);

    echo json_encode([
        'success'     => true,
        'role'        => $role,
        'state'       => $state,
        'can_request' => $can === true,
        'reason'      => $can === true ? null : $can,
    ]);
    exit;
}

// =============================================================
// ACTION: REQUEST
// =============================================================
if ($action === 'request') {
    $role = trim((string)param('role', ''));
    if (!in_array($role, $REQUESTABLE, true)) {
        fail('Unknown role.');
    }

    // An account can only ever request the role it was registered for.
    // Without this a customer could request rider access on their shopping
    // account, which is exactly the interlinking account types remove.
    require_once __DIR__ . '/../includes/account_type.php';
    $accountType = accountTypeOf($dbh, $user_id);
    if ($accountType !== $role) {
        fail('This is ' . accountTypeLabel($accountType) . ', so it cannot become '
            . accountTypeLabel($role) . '. Register separately in the ' . ucfirst($role) . ' app.');
    }

    // Checked before submitting so the refusal carries the specific reason
    // rather than a generic failure.
    $can = canRequestRole($user_id, $role);
    if ($can !== true) {
        fail($can);
    }

    if (!submitRoleRequest($user_id, $role)) {
        fail('Could not submit your request. Please try again.');
    }

    echo json_encode([
        'success' => true,
        'state'   => 'pending',
        'message' => 'Your request has been sent. An administrator will review it shortly.',
    ]);
    exit;
}

// =============================================================
// ACTION: MY REQUESTS
// =============================================================
if ($action === 'my_requests') {
    $requests = array_map(function ($r) {
        return [
            'id'           => (int)$r['id'],
            'role'         => $r['requested_role'],
            'status'       => $r['status'],
            'admin_notes'  => $r['admin_notes'],
            'created_at'   => $r['created_at'],
            'processed_at' => $r['processed_at'],
        ];
    }, getUserRoleRequests($user_id));

    echo json_encode(['success' => true, 'requests' => $requests]);
    exit;
}

fail('Invalid action');