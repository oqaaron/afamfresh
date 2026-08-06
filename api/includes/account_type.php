<?php
// =============================================================
// includes/account_type.php
//
// One account, one purpose.
//
// `users.account_type` is fixed when the account is created and says
// what the account is FOR: shopping, delivering, or selling. It is
// deliberately NOT the same thing as `user_roles`, which says what an
// account is currently ALLOWED to do:
//
//   account_type  what you signed up as      never changes
//   user_roles    what an admin has approved granted on approval
//
// A rider registers in the Rider app, gets account_type='rider'
// immediately, and only receives the 'rider' role once an admin
// approves the request. Until then they can sign in and see the
// pending screen, and nothing else.
//
// Why this exists: previously every account carried an implicit
// 'user' (customer) baseline — registration wrote no user_roles row
// at all, getUserRolesData() fell back to [current_role], and
// switch_role treated 'user' as always granted. The result was that a
// rider account could call customer endpoints and shop. Verified: a
// signed-in rider got a 200 from orders.php?action=list.
// =============================================================

/**
 * The app-role names the clients use, mapped to account types.
 *
 * The clients speak `user_roles` vocabulary ('user'), because that is
 * what BuildConfig.APP_ROLE and switch_role already use. The database
 * column says 'customer', which is clearer. This is the only place the
 * two vocabularies meet.
 */
function accountTypeForAppRole($appRole) {
    switch (trim((string)$appRole)) {
        case 'rider':       return 'rider';
        case 'vendor':      return 'vendor';
        case 'wholesaler':  return 'wholesaler';
        case 'user':
        case 'customer':    return 'customer';
        default:            return null;
    }
}

/** The account type for a user id, or null if the account is gone. */
function accountTypeOf($dbh, $userId) {
    $stmt = $dbh->prepare("SELECT account_type FROM users WHERE id = ?");
    $stmt->execute([$userId]);
    $type = $stmt->fetchColumn();
    return $type === false ? null : $type;
}

/** Human wording for a refusal, e.g. "This is a rider account." */
function accountTypeLabel($type) {
    switch ($type) {
        case 'rider':      return 'a rider account';
        case 'vendor':     return 'a vendor account';
        case 'wholesaler': return 'a wholesaler account';
        default:           return 'a customer account';
    }
}

/**
 * Refuses the request unless the signed-in account is of $required type.
 *
 * Server-side on purpose. The three apps are a UX split, not a security
 * boundary — anyone can point curl at these endpoints, so the check has
 * to live here rather than in the client.
 *
 * Echoes the standard envelope and exits on failure, matching the
 * `fail()` helpers in profile.php / rider.php / roles.php.
 */
function requireAccountType($dbh, $userId, $required) {
    $type = accountTypeOf($dbh, $userId);
    if ($type === $required) {
        return $type;
    }

    $wanted = accountTypeLabel($required);
    $actual = accountTypeLabel($type);
    echo json_encode([
        'success' => false,
        'error'   => "This is {$actual}, so it can't be used here. "
                   . "You need {$wanted} for that.",
        'account_type' => $type,
    ]);
    exit;
}