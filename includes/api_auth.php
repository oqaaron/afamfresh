<?php
// =============================================================
// includes/api_auth.php — "this user_id is really you".
// =============================================================
// A family of endpoints took user_id straight from the query
// string and answered for whoever was named, with no session
// check: vendor-profile, vendor-earnings, vendor-products,
// vendor-notifications and surplus-orders. Incrementing that
// number walked through the platform's vendors and returned
// their revenue, commission rates and net earnings. All of it was
// reachable from the open internet.
//
// One helper rather than five copies of the check, because five
// copies is how the gap opened: each endpoint was written to the
// shape of the one before it, and none of them had the check to
// copy.
//
// Requires the session to have been started -- config.php does
// that -- and does not start one itself, so a caller cannot end
// up with a fresh empty session and read it as "not signed in".
// =============================================================

/**
 * Resolve the user id an endpoint should act on.
 *
 * Returns the signed-in user's id. If $requested names someone
 * else, the request is refused outright unless the caller holds
 * an admin session, since the admin pages legitimately read other
 * people's records. A $requested of 0 means "me".
 *
 * Sends the error response and exits on refusal; a caller that
 * gets a return value can trust it.
 *
 * @param int $requested user id from the request, or 0
 * @return int the user id to act on
 */
function requireOwnUserId($requested) {
    $isAdmin = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
    $sessionUserId = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

    if ($sessionUserId === 0 && !$isAdmin) {
        http_response_code(401);
        echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
        exit;
    }

    $requested = (int)$requested;
    $userId = $requested !== 0 ? $requested : $sessionUserId;

    if ($userId === 0) {
        http_response_code(400);
        echo json_encode(['success' => false, 'error' => 'user_id is required']);
        exit;
    }

    if ($userId !== $sessionUserId && !$isAdmin) {
        // Same wording whether or not that account exists, so this cannot be
        // used to find out which user ids are vendors.
        http_response_code(403);
        echo json_encode(['success' => false, 'error' => 'Not your account.']);
        exit;
    }

    return $userId;
}
