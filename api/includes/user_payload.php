<?php
// =============================================================
// AfamFresh - the one place a user object is built for the app
// =============================================================
// Every endpoint that returns a user MUST go through
// buildUserPayload(). Before this existed, each of the five
// user-returning branches in auth.php assembled its own array by
// hand, and one of them forgetting a key was not a hypothetical:
// none of them sent 'roles', which the Android User model declares
// as a non-null List<String>. Gson populates fields reflectively
// and skips the Kotlin constructor, so an absent key lands as a
// genuine null despite the type — and that crashed login on
// user.roles.joinToString(",") for every user.
//
// One builder means a new field is added once and every entry
// point gets it, and a missing key is impossible by construction.
// =============================================================

/**
 * Active roles + current role for a user.
 *
 * Lives here rather than in auth.php so profile.php can reach it
 * too. It is a plain global function, so auth.php's existing call
 * sites keep working unchanged.
 */
function getUserRolesData($dbh, $userId) {
    $stmt = $dbh->prepare("SELECT role FROM user_roles WHERE user_id = ? AND status = 'active'");
    $stmt->execute([$userId]);
    $roles = $stmt->fetchAll(PDO::FETCH_COLUMN);

    // Backticks are NOT optional here. Unquoted, MariaDB parses `current_role`
    // as the built-in CURRENT_ROLE() function, which returns NULL on a normal
    // connection — so this SELECT silently ignored the column entirely and
    // every account fell through to the 'user' default below, whatever role
    // they actually held. Role switching appeared to work and then reverted
    // on the next profile refresh.
    $currentStmt = $dbh->prepare("SELECT `current_role` FROM users WHERE id = ?");
    $currentStmt->execute([$userId]);
    $currentRole = $currentStmt->fetchColumn() ?: 'user';

    // A user with no active user_roles row (created before that table
    // was populated, or immediately after registration) still has at
    // least their current_role.
    // A missing user_roles row must NOT invent a role.
    //
    // This used to fall back to [$currentRole], which reported a rider who
    // had registered but not yet been approved as already holding the 'rider'
    // role — the payload contradicting the approval workflow.
    //
    // Customers are the one safe fallback: a customer account is usable the
    // moment it exists, so an older row with no user_roles entry is still a
    // customer. Anything else has to be granted by an admin.
    if (empty($roles)) {
        $typeStmt = $dbh->prepare("SELECT account_type FROM users WHERE id = ?");
        $typeStmt->execute([$userId]);
        $roles = ($typeStmt->fetchColumn() === 'customer') ? ['user'] : [];
    }

    return ['roles' => $roles, 'current_role' => $currentRole];
}

/**
 * Absolute base URL of this installation, e.g. http://192.168.3.135/afamfresh
 *
 * Derived from the request rather than configured, because this app is
 * reached by several different hosts (localhost from the server itself, a
 * LAN IP from the phone, and a domain in production) and a hardcoded value
 * would be wrong for at least two of them.
 *
 * Needed because avatars must be returned as ABSOLUTE urls. The existing
 * product images return a bare filename ("apple.png") straight into Coil,
 * which cannot resolve it — those images are silently broken in the app
 * today. Not repeating that here.
 */
function appBaseUrl() {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $host   = $_SERVER['HTTP_HOST'] ?? 'localhost';
    // SCRIPT_NAME is /afamfresh/api/auth.php -> two dirnames up is /afamfresh
    $root   = str_replace('\\', '/', dirname(dirname($_SERVER['SCRIPT_NAME'] ?? '')));
    $root   = rtrim($root, '/');
    if ($root === '.' ) { $root = ''; }
    return $scheme . '://' . $host . $root;
}

/**
 * The complete user object the app expects, for a given user id.
 *
 * Returns null when the user does not exist, so callers can 404 sensibly.
 */
function buildUserPayload($dbh, $userId) {
    $stmt = $dbh->prepare(
        "SELECT id, fname, lname, email, mobile, area, address,
                google_picture, profile_picture, password, google_id,
                loyalty_points, notification_preferences, account_type
         FROM users WHERE id = ?"
    );
    $stmt->execute([$userId]);
    $u = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$u) {
        return null;
    }

    $roleData = getUserRolesData($dbh, $userId);

    // Avatar precedence: an uploaded picture wins, the Google photo is the
    // fallback, otherwise nothing. profile_picture holds a bare filename and
    // needs the base url; google_picture is already an absolute remote url.
    $avatarUrl = null;
    if (!empty($u['profile_picture'])) {
        // storageUrl, not appBaseUrl: on Cloud Run avatars live in a bucket
        // and are not served from this host at all.
        require_once __DIR__ . '/storage.php';
        $avatarUrl = storageUrl('avatars/' . $u['profile_picture']);
    } elseif (!empty($u['google_picture'])) {
        $avatarUrl = $u['google_picture'];
    }

    // Same default as includes/classes/NotificationManager.php, which is the
    // code that actually consults these. Coerced to real booleans so the
    // client never has to interpret "0"/"1"/null.
    $prefs = json_decode($u['notification_preferences'] ?? '', true);
    if (!is_array($prefs)) {
        $prefs = [];
    }

    // Whether this account can be asked for its current password. Google
    // sign-in rows have password IS NULL, so the app must not offer them a
    // change-password form or demand a password to change their email.
    $hasPassword = isset($u['password']) && $u['password'] !== '';

    return [
        // Cast: the Android User model declares id as String.
        'id'     => (string)$u['id'],
        // KEEP THIS KEY. User.name is the only name the app has today and
        // AuthRepository caches it. fname/lname are additive.
        'name'   => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
        'fname'  => $u['fname'] ?? '',
        'lname'  => $u['lname'] ?? '',
        'email'  => $u['email'],
        'mobile' => $u['mobile'],
        'area'    => $u['area'],
        'address' => $u['address'],
        'avatar_url'        => $avatarUrl,
        'has_password'      => $hasPassword,
        'is_google_account' => !empty($u['google_id']),
        'loyalty_points'    => (int)($u['loyalty_points'] ?? 0),
        'notification_preferences' => [
            'email' => array_key_exists('email', $prefs) ? (bool)$prefs['email'] : true,
            'push'  => array_key_exists('push',  $prefs) ? (bool)$prefs['push']  : true,
        ],
        'roles'        => $roleData['roles'],
        'current_role' => $roleData['current_role'],
        // What this account is FOR — fixed at registration, unlike `roles`,
        // which is what an admin has approved so far. The apps use this to
        // refuse an account that belongs to a different app.
        'account_type' => $u['account_type'] ?? 'customer',
    ];
}