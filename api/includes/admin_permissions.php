<?php
/**
 * includes/admin_permissions.php — role-based permissions for admin accounts.
 *
 * Every admin used to be identical: logged in or not, nothing else checked
 * anywhere. This defines named permission keys, maps roles onto sets of
 * them, and gates pages/actions on those keys instead of the bare
 * admin_logged_in flag.
 *
 * A new role is a new entry in ADMIN_ROLES below — no migration, no schema
 * change. admin.role (see the 2026-08-15-admin-roles-and-audit-log.sql
 * migration) just has to match a key here.
 */

const ADMIN_ROLES = [
    // '*' = every permission, unconditionally. The only role that can
    // manage other admin accounts or view the audit log.
    'super_admin' => ['*'],

    // Dispatches orders: status changes and rider assignment, plus editing
    // a product's non-price fields (images, description, category,
    // quantity). Deliberately excludes products.manage_pricing,
    // configuration.manage, payouts.manage, Bulk.manage_refunds,
    // vendors.manage, and admins.manage.
    //
    // reports.view (not reports.view_financial) only unlocks dashboard.php
    // itself, which branches internally on reports.view_financial to show a
    // personal activity summary instead of business-wide revenue/fees —
    // see admin/dashboard.php. reconciliation.php/export-reconciliation.php/
    // routing-check.php all require reports.view_financial directly.
    'dispatcher' => [
        'orders.manage',
        'Bulk.dispatch',
        'products.manage_operational',
        'reports.view',
    ],
];

/** The current session's permission set. Empty if not logged in as admin, or role is unrecognised. */
function adminPermissions(): array {
    $role = $_SESSION['admin_role'] ?? '';
    return ADMIN_ROLES[$role] ?? [];
}

/**
 * True for a session that authenticated BEFORE admin roles existed.
 *
 * Such a session has admin_logged_in = true but no admin_role key at all, so
 * adminPermissions() returns [] and every guarded action is refused — while
 * the pages themselves still render, because they were reached with a valid
 * login. The symptom is a control that appears to work, silently fails, and
 * reverts: a select that snaps back, a toggle that un-toggles.
 *
 * Told apart from a real permission refusal because the fix is completely
 * different. "You lack rights" is permanent and the answer is to ask a super
 * admin; this is a stale cookie and the answer is to sign in again.
 *
 * Note the check is for the key's ABSENCE, not its emptiness — login.php has
 * always written a non-empty value (defaulting to 'super_admin'), so a missing
 * key can only mean the session predates that code.
 */
function adminSessionPredatesRoles(): bool {
    return !empty($_SESSION['admin_logged_in']) && !array_key_exists('admin_role', $_SESSION);
}

function adminHasPermission(string $permission): bool {
    $perms = adminPermissions();
    return in_array('*', $perms, true) || in_array($permission, $perms, true);
}

/** True if the session holds ANY of the given permissions — for pages one of several roles may view. */
function adminHasAnyPermission(array $permissions): bool {
    foreach ($permissions as $p) {
        if (adminHasPermission($p)) return true;
    }
    return false;
}

/**
 * Web pages: must be logged in AND hold the permission, else a plain 403
 * page — not a redirect to login. They ARE logged in; bouncing them back to
 * the login form when the real problem is "you don't have rights" is
 * confusing and looks like a bug.
 */
function requireAdminPermission(string $permission): void {
    require_once __DIR__ . '/../admin/auth_check.php';
    requireAdminLoginWeb();
    // A pre-roles session cannot hold any permission, so send it to the login
    // form rather than showing "Access denied" for something the account is
    // very likely entitled to.
    if (adminSessionPredatesRoles()) {
        header('Location: logout.php');
        exit;
    }
    if (!adminHasPermission($permission)) {
        http_response_code(403);
        echo '<!DOCTYPE html><html><head><title>Access denied</title>'
            . '<script src="https://cdn.tailwindcss.com"></script></head>'
            . '<body class="bg-gray-100 flex items-center justify-center min-h-screen">'
            . '<div class="bg-white p-8 rounded-xl shadow max-w-md text-center">'
            . '<h1 class="text-xl font-bold text-red-700 mb-2">Access denied</h1>'
            . '<p class="text-gray-600 mb-4">Your account does not have permission to view this page.</p>'
            . '<a href="dashboard.php" class="text-green-700 font-semibold">Back to dashboard</a>'
            . '</div></body></html>';
        exit;
    }
}

/** Same idea for a page reachable by more than one role (e.g. admin-dashboard.php). */
function requireAnyAdminPermission(array $permissions): void {
    require_once __DIR__ . '/../admin/auth_check.php';
    requireAdminLoginWeb();
    if (adminSessionPredatesRoles()) {
        header('Location: logout.php');
        exit;
    }
    if (!adminHasAnyPermission($permissions)) {
        http_response_code(403);
        echo '<!DOCTYPE html><html><head><title>Access denied</title>'
            . '<script src="https://cdn.tailwindcss.com"></script></head>'
            . '<body class="bg-gray-100 flex items-center justify-center min-h-screen">'
            . '<div class="bg-white p-8 rounded-xl shadow max-w-md text-center">'
            . '<h1 class="text-xl font-bold text-red-700 mb-2">Access denied</h1>'
            . '<p class="text-gray-600 mb-4">Your account does not have permission to view this page.</p>'
            . '<a href="dashboard.php" class="text-green-700 font-semibold">Back to dashboard</a>'
            . '</div></body></html>';
        exit;
    }
}

/** JSON endpoints (api/api/admin/*.php): same check, JSON 403 body. */
function requireAdminPermissionApi(string $permission): void {
    if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
        http_response_code(401);
        echo json_encode(['success' => false, 'error' => 'Unauthorized. Please log in.']);
        exit;
    }
    // 401, not 403: this is an authentication problem wearing a permission
    // problem's clothes, and the caller must be told to sign in rather than to
    // go and ask for rights it may already have.
    if (adminSessionPredatesRoles()) {
        http_response_code(401);
        echo json_encode([
            'success' => false,
            'error'   => 'Your admin session was started before roles were introduced. '
                       . 'Please sign out and sign in again.',
        ]);
        exit;
    }
    if (!adminHasPermission($permission)) {
        http_response_code(403);
        echo json_encode(['success' => false, 'error' => 'Your account does not have permission to do that.']);
        exit;
    }
}
