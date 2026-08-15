<?php
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

session_start();
require_once '../admin/includes/config.php';
// getUserRolesData() and buildUserPayload() live here so profile.php can use
// them too. Every branch below that returns a user goes through
// buildUserPayload(), which is what guarantees 'roles' is always present —
// see the note in that file for why a missing key is a crash, not a nicety.
require_once __DIR__ . '/../includes/user_payload.php';
// One account, one purpose: see the header of this file for why the implicit
// customer baseline had to go.
require_once __DIR__ . '/../includes/account_type.php';
require_once __DIR__ . '/../includes/rate_limit.php';
header('Content-Type: application/json');

$action = $_GET['action'] ?? '';

// Helper: generate a unique token (session ID or random)
function generateToken() {
    return bin2hex(random_bytes(32));
}

// ============================================================
// ACTION: LOGIN
// ============================================================
if ($action == 'login') {
    $input = json_decode(file_get_contents('php://input'), true);
    $email = trim($input['email'] ?? '');
    $password = $input['password'] ?? '';
    // Which app is asking. Absent for installs that predate the split, which
    // are all Customer installs — hence the default.
    $appType = accountTypeForAppRole($input['app_role'] ?? 'customer') ?? 'customer';

    // Bucketed by IP AND by the submitted email, so both "one IP hammering
    // many accounts" and "many IPs hammering one account" are caught.
    if (rateLimited($dbh, 'login:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)
        || ($email !== '' && rateLimited($dbh, 'login:id:' . strtolower($email), 5, 300))) {
        failRateLimited();
    }

    try {
        $stmt = $dbh->prepare("SELECT id, fname, lname, email, mobile, password, account_type FROM users WHERE email = ?");
        $stmt->execute([$email]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);

        if ($user && password_verify($password, $user['password'])) {
            // Right password, wrong app. Refused here rather than after
            // sign-in, so the person is told plainly instead of landing in a
            // workspace that then fails every call.
            //
            // Checked AFTER the password so this cannot be used to discover
            // which addresses are registered as riders.
            if ($user['account_type'] !== $appType) {
                echo json_encode([
                    'success' => false,
                    'error'   => 'This is ' . accountTypeLabel($user['account_type'])
                               . ", so it can't be used in this app. "
                               . 'Please use the AfamFresh app for that account.',
                ]);
                exit;
            }

            $token = generateToken();
            session_regenerate_id(true);
            $_SESSION['user_id'] = $user['id'];
            $_SESSION['user_name'] = $user['fname'] . ' ' . $user['lname'];
            $_SESSION['user_email'] = $user['email'];
            $_SESSION['auth_token'] = $token;

            echo json_encode([
                'success' => true,
                'token' => $token,
                'user' => buildUserPayload($dbh, $user['id'])
            ]);
        } else {
            echo json_encode(['success' => false, 'error' => 'Invalid credentials']);
        }
    } catch (PDOException $e) {
        error_log("Login DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Database error occurred']);
    }
    exit;
}

// ============================================================
// ACTION: REGISTER
// ============================================================
if ($action == 'register') {
    $input = json_decode(file_get_contents('php://input'), true);
    $name = trim($input['name'] ?? '');
    $email = trim($input['email'] ?? '');
    $password = $input['password'] ?? '';
    $mobile = trim($input['mobile'] ?? '');
    $role = trim($input['role'] ?? 'user');
    
    $nameParts = explode(' ', $name, 2);
    $fname = $nameParts[0] ?? '';
    $lname = $nameParts[1] ?? '';

    // Same bucketing as login: by IP and by the submitted email, so this
    // can't be scripted into unlimited account creation, and the "Email
    // already registered" response can't be used as an unthrottled
    // enumeration oracle.
    if (rateLimited($dbh, 'register:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)
        || ($email !== '' && rateLimited($dbh, 'register:id:' . strtolower($email), 5, 300))) {
        failRateLimited();
    }

    try {
        // Check if email already exists
        $checkStmt = $dbh->prepare("SELECT id FROM users WHERE email = ?");
        $checkStmt->execute([$email]);
        if ($checkStmt->fetch()) {
            echo json_encode(['success' => false, 'error' => 'Email already registered']);
            exit;
        }
        
        // What the account is FOR, decided once, here. `role` is the app's
        // BuildConfig.APP_ROLE — the Rider app sends "rider", the Customer app
        // "user". It used to be read and then ignored, which is how every
        // account ended up being a customer that could also do anything else.
        $accountType = accountTypeForAppRole($role) ?? 'customer';

        $hashedPassword = password_hash($password, PASSWORD_DEFAULT);
        $stmt = $dbh->prepare("INSERT INTO users (fname, lname, email, password, mobile, area, address, account_type, `current_role`) VALUES (?, ?, ?, ?, ?, 'Not specified', 'Not specified', ?, ?)");
        $result = $stmt->execute([
            $fname, $lname, $email, $hashedPassword, $mobile,
            $accountType,
            // current_role mirrors the account type so the payload cannot
            // contradict it. `current_role` is a MariaDB reserved word.
            $accountType === 'customer' ? 'user' : $accountType,
        ]);

        if ($result) {
            $userId = $dbh->lastInsertId();

            // A customer is usable immediately. A rider or vendor is NOT: they
            // get no user_roles row here, so the workspace stays locked until
            // an admin approves the request they file from the pending screen
            // (api/roles.php -> admin/role-requests.php).
            if ($accountType === 'customer') {
                $dbh->prepare(
                    "INSERT INTO user_roles (user_id, role, status) VALUES (?, 'user', 'active')
                     ON DUPLICATE KEY UPDATE status = 'active'"
                )->execute([$userId]);
            }
            // Queued, not sent inline: registration must not wait on Brevo, and
            // must not fail because Brevo is down. The worker delivers it.
            require_once __DIR__ . '/../includes/notifications.php';
            notifyWelcome($userId, $fname, $accountType);

            $token = generateToken();
            session_regenerate_id(true);
            $_SESSION['user_id'] = $userId;
            $_SESSION['user_name'] = $name;
            $_SESSION['user_email'] = $email;
            $_SESSION['auth_token'] = $token;

            echo json_encode([
                'success' => true,
                'token' => $token,
                'message' => 'Registration successful',
                'user' => buildUserPayload($dbh, $userId)
            ]);
        } else {
            echo json_encode(['success' => false, 'error' => 'Registration failed']);
        }
    } catch (PDOException $e) {
        error_log("Register DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Database error occurred']);
    }
    exit;
}

// ============================================================
// ACTION: SWITCH ROLE
// ============================================================
// The Android app has declared ApiService.switchRole against this action
// since the beginning, but the action did not exist — so AuthRepository
// short-circuited and wrote the new role to SharedPreferences only. The
// server never knew, which meant the choice was per-device, invisible to
// the backend, and silently reverted the moment anything re-read the user
// from the server.
//
// Only a role the user actually holds (an ACTIVE row in user_roles) is
// accepted: current_role is what the app uses to decide which screens to
// show, so letting a client set it freely would be self-granted access to
// the vendor and rider surfaces.
if ($action == 'switch_role') {
    if (!isset($_SESSION['user_id'])) {
        echo json_encode(['success' => false, 'error' => 'Not logged in']);
        exit;
    }

    $input = json_decode(file_get_contents('php://input'), true);
    $role = trim($input['role'] ?? $_POST['role'] ?? $_GET['role'] ?? '');

    if ($role === '') {
        echo json_encode(['success' => false, 'error' => 'No role supplied.']);
        exit;
    }

    try {
        $check = $dbh->prepare(
            "SELECT 1 FROM user_roles WHERE user_id = ? AND role = ? AND status = 'active'"
        );
        $check->execute([$_SESSION['user_id'], $role]);

        // 'user' is every account's implicit baseline: registration does not
        // always write a user_roles row, so requiring one here would stop
        // people switching back to the customer view.
        if (!$check->fetch() && $role !== 'user') {
            echo json_encode(['success' => false, 'error' => "You don't have access to that role."]);
            exit;
        }

        $stmt = $dbh->prepare("UPDATE users SET `current_role` = ? WHERE id = ?");
        $stmt->execute([$role, $_SESSION['user_id']]);

        echo json_encode([
            'success' => true,
            'current_role' => $role,
            'user' => buildUserPayload($dbh, $_SESSION['user_id'])
        ]);
    } catch (PDOException $e) {
        // current_role is an ENUM, so an unknown value is rejected by the
        // column itself as well as by the check above.
        error_log("Switch role error for user {$_SESSION['user_id']}: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not switch role.']);
    }
    exit;
}

// ============================================================
// ACTION: GET CURRENT USER
// ============================================================
if ($action == 'me') {
    if (isset($_SESSION['user_id'])) {
        try {
            $payload = buildUserPayload($dbh, $_SESSION['user_id']);
            if ($payload) {
                echo json_encode(['success' => true, 'user' => $payload]);
            } else {
                echo json_encode(['success' => false, 'error' => 'User not found']);
            }
        } catch (PDOException $e) {
            error_log("GetUser DB error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Database error']);
        }
    } else {
        echo json_encode(['success' => false, 'error' => 'Not logged in']);
    }
    exit;
}

// ============================================================
// ACTION: LOGOUT
// ============================================================
if ($action == 'logout') {
    if (!empty($_SESSION['user_id'])) {
        // Stop pushes from targeting a device that just signed out —
        // an FCM token is per-install and outlives the session otherwise.
        $clearStmt = $dbh->prepare("UPDATE users SET fcm_token = NULL WHERE id = ?");
        $clearStmt->execute([$_SESSION['user_id']]);
    }
    session_destroy();
    echo json_encode(['success' => true]);
    exit;
}

// ============================================================
// ACTION: GOOGLE LOGIN (FIXED – supports JSON, POST, GET)
// ============================================================
if ($action == 'google_login') {
    // --- Try to get id_token from multiple sources ---
    $idToken = null;
    $input = json_decode(file_get_contents('php://input'), true);
    if (isset($input['id_token'])) {
        $idToken = trim($input['id_token']);
    }
    if (empty($idToken) && isset($_POST['id_token'])) {
        $idToken = trim($_POST['id_token']);
    }
    if (empty($idToken) && isset($_GET['id_token'])) {
        $idToken = trim($_GET['id_token']);
    }
    
    if (empty($idToken)) {
        echo json_encode(['success' => false, 'error' => 'ID token missing']);
        exit;
    }

    // Which app is asking, read the same three ways as the token above because
    // the client posts this form-encoded while older builds sent JSON.
    //
    // This block did not exist, and its absence broke role requests entirely:
    // the INSERT below omitted account_type, so every Google account was
    // created with the column default 'customer'. api/roles.php then refused
    // "request vendor access" at its account-type check before a row could
    // reach role_requests, so the admin queue stayed empty with nothing to
    // show for it.
    $appRoleRaw = $input['app_role'] ?? $_POST['app_role'] ?? $_GET['app_role'] ?? 'customer';
    $appType = accountTypeForAppRole($appRoleRaw) ?? 'customer';

    // Verify the ID token with Google
    //
    // TLS verification was off here — the exact connection that decides
    // whether a Google ID token is genuine was unauthenticated, so a MITM
    // could forge the "yes this token is valid" response and impersonate
    // any Google account.
    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, "https://oauth2.googleapis.com/tokeninfo?id_token=$idToken");
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, 10);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 2);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    
    if ($httpCode !== 200) {
        error_log("Google tokeninfo failed, HTTP: $httpCode, response: $response");
        echo json_encode(['success' => false, 'error' => 'Invalid ID token']);
        exit;
    }
    
    $userInfo = json_decode($response, true);
    $google_id = $userInfo['sub'] ?? null;
    $email = $userInfo['email'] ?? null;
    $name = $userInfo['name'] ?? null;
    $picture = $userInfo['picture'] ?? null;
    
    // --- Security Check: Verify audience (aud) matches our Web Client ID ---
    //
    // The audience is what stops a Google token minted for some other app
    // being replayed here to log in as that user. It must be the WEB client
    // id — the value the app passes to requestIdToken — not an Android one.
    //
    // No default. A wrong default is how this broke before: the literal was a
    // client from an unrelated project, so every sign-in was rejected. An
    // unset value now refuses the sign-in loudly rather than falling back to
    // a guess.
    $expectedAud = trim((string)env('GOOGLE_WEB_CLIENT_ID', ''));
    if ($expectedAud === '') {
        error_log('Google Sign-In: GOOGLE_WEB_CLIENT_ID is not set — refusing to verify a token with no known audience.');
        echo json_encode(['success' => false, 'error' => 'Google Sign-In is not configured on this server.']);
        exit;
    }

    // Note the !isset half. This was `isset($aud) && $aud !== $expected`,
    // which ACCEPTED a token carrying no aud claim at all — the one case the
    // check exists to catch.
    if (!isset($userInfo['aud']) || $userInfo['aud'] !== $expectedAud) {
        error_log("Google Sign-In: Invalid audience. Expected: $expectedAud, Got: " . ($userInfo['aud'] ?? 'null'));
        echo json_encode(['success' => false, 'error' => 'Invalid token audience']);
        exit;
    }
    
    if (!$google_id || !$email) {
        error_log("Google Sign-In: Missing google_id or email from token info");
        echo json_encode(['success' => false, 'error' => 'Failed to extract user info']);
        exit;
    }
    
    try {
        // Check if user exists by google_id or email
        $stmt = $dbh->prepare("SELECT * FROM users WHERE google_id = ? OR email = ?");
        $stmt->execute([$google_id, $email]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);
        
        if ($user) {
            // Right account, wrong app — the check the password path has done
            // all along at the top of this file, missing here. Without it
            // Google Sign-In walked a customer account straight into the
            // Vendor and Rider apps, which is the one thing account_type
            // exists to prevent.
            //
            // Checked before google_id is written, so a refused sign-in
            // leaves no trace on the account.
            if ($user['account_type'] !== $appType) {
                echo json_encode([
                    'success' => false,
                    'error'   => 'This is ' . accountTypeLabel($user['account_type'])
                               . ", so it can't be used in this app. "
                               . 'Please use the AfamFresh app for that account.',
                ]);
                exit;
            }

            // If user exists but no google_id, update it
            if (empty($user['google_id'])) {
                $updateStmt = $dbh->prepare("UPDATE users SET google_id = ?, google_picture = ? WHERE id = ?");
                $updateStmt->execute([$google_id, $picture, $user['id']]);
            }
            // Login the user
            $token = generateToken();
            session_regenerate_id(true);
            $_SESSION['user_id'] = $user['id'];
            $_SESSION['user_name'] = $user['fname'] . ' ' . $user['lname'];
            $_SESSION['user_email'] = $user['email'];
            $_SESSION['auth_token'] = $token;
            
            echo json_encode([
                'success' => true,
                'token' => $token,
                'user' => buildUserPayload($dbh, $user['id'])
            ]);
        } else {
            // Create new user
            $fname = explode(' ', $name)[0] ?? '';
            $lname = implode(' ', array_slice(explode(' ', $name), 1)) ?? '';
            // account_type and current_role are set here for the same reason
            // the password registration above sets them: the account type is
            // fixed at creation and nothing later can change it. Leaving them
            // to the column default made every Google account a customer.
            $insertStmt = $dbh->prepare("
                INSERT INTO users (fname, lname, email, google_id, google_picture, area, address, mobile, account_type, `current_role`)
                VALUES (?, ?, ?, ?, ?, 'Not specified', 'Not specified', ?, ?, ?)
            ");
            // Set mobile to NULL (if column allows NULL) or an empty string
            $mobile = null; // Let database use default NULL
            $result = $insertStmt->execute([
                $fname, $lname, $email, $google_id, $picture, $mobile,
                $appType,
                // Mirrors the account type so the payload cannot contradict
                // it. `current_role` is a MariaDB reserved word.
                $appType === 'customer' ? 'user' : $appType,
            ]);
            
            if ($result) {
                $userId = $dbh->lastInsertId();

                // Same welcome as the password path. This branch creates real
                // accounts too — a Google sign-in for an unknown address IS a
                // registration, and it is the route all three of the current
                // production accounts came in through.
                require_once __DIR__ . '/../includes/notifications.php';
                notifyWelcome($userId, $fname, $appType);

                $token = generateToken();
                session_regenerate_id(true);
                $_SESSION['user_id'] = $userId;
                $_SESSION['user_name'] = $name;
                $_SESSION['user_email'] = $email;
                $_SESSION['auth_token'] = $token;
                
                echo json_encode([
                    'success' => true,
                    'token' => $token,
                    'user' => buildUserPayload($dbh, $userId)
                ]);
            } else {
                $errorInfo = $insertStmt->errorInfo();
                error_log("Google Sign-Up INSERT failed: " . print_r($errorInfo, true));
                echo json_encode(['success' => false, 'error' => 'Registration failed: ' . $errorInfo[2]]);
            }
        }
    } catch (PDOException $e) {
        error_log("Google Sign-In DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Database error occurred']);
    }
    exit;
}

// ============================================================
// ACTION: FORGOT PASSWORD (REQUEST RESET)
// ============================================================
if ($action == 'forgot_password') {
    $input = json_decode(file_get_contents('php://input'), true);
    if ($input !== null) {
        $email = trim($input['email'] ?? '');
    } else {
        $email = trim($_POST['email'] ?? '');
    }
    
    if (!$email) {
        echo json_encode(['success' => false, 'message' => 'Email address required']);
        exit;
    }

    if (rateLimited($dbh, 'forgot_password:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)
        || rateLimited($dbh, 'forgot_password:id:' . strtolower($email), 5, 300)) {
        failRateLimited();
    }

    // Always report success regardless of whether the email is registered —
    // an error here would let a caller test which addresses have accounts.
    try {
        // fname is selected purely so the email can address the recipient;
        // nothing downstream branches on it. (The column is fname/lname —
        // there is no `name` column on users.)
        $stmt = $dbh->prepare("SELECT id, fname FROM users WHERE email = ?");
        $stmt->execute([$email]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);

        if ($user) {
            $rawToken = bin2hex(random_bytes(32));
            $hashedToken = hash('sha256', $rawToken);
            $expiry = date('Y-m-d H:i:s', strtotime('+30 minutes'));

            $updateStmt = $dbh->prepare("UPDATE users SET reset_token = ?, reset_token_expiry = ? WHERE id = ?");
            $updateStmt->execute([$hashedToken, $expiry, $user['id']]);

            // Must be the app's deep link, not a web URL — the app only
            // registers an intent filter for <scheme>://reset-password.
            //
            // The scheme is per-app: Customer, Rider and Vendor are separate
            // installs, and if all three claimed "afamfresh" then tapping a
            // reset link would raise an app-chooser instead of opening the app
            // that asked for it. The caller supplies its scheme, checked
            // against a fixed list so a request cannot inject an arbitrary URL
            // into an email we send on the user's behalf.
            // Read from JSON or the form body, the same way $email is read
            // above — the app posts this action form-encoded, so $input is null.
            $requestedScheme = trim((string)($input['scheme'] ?? $_POST['scheme'] ?? ''));
            $allowedSchemes  = ['afamfresh', 'afamfresh-rider', 'afamfresh-vendor'];
            $scheme = in_array($requestedScheme, $allowedSchemes, true)
                ? $requestedScheme
                : 'afamfresh';   // installs predating this send nothing

            // An https link to our own bridge page, which then hands off to the
            // app — not the raw afamfresh:// scheme.
            //
            // The custom scheme was both a spam signal (these emails were
            // landing in spam) and frequently dead: many mail clients will not
            // linkify a non-HTTP href, and some strip it. reset-password.php
            // does the handoff and explains itself when the app is missing.
            require_once __DIR__ . '/../includes/user_payload.php';
            $resetLink = appBaseUrl() . '/reset-password.php?token=' . rawurlencode($rawToken)
                       . '&scheme=' . rawurlencode($scheme);
            $subject = 'Reset your AfamFresh password';

            // Sent through Brevo, not mail(). PHP's mail() needs a local MTA,
            // and the php:8.2-apache image this runs in on Cloud Run has none —
            // the call returns false and the email simply never exists, while
            // the response below still reports success.
            //
            // Both a text and an HTML part, deliberately. The link uses a custom
            // scheme (afamfresh://, afamfresh-rider://, afamfresh-vendor://) and
            // a good number of mail clients will not linkify, or will strip, a
            // non-http href — so the plain-text copy is the reliable path and
            // the HTML one is the convenience.
            $safeLink = htmlspecialchars($resetLink, ENT_QUOTES, 'UTF-8');
            $textBody = "Hello,\n\n"
                . "You requested a password reset for your AfamFresh account.\n\n"
                . "Open this link on your phone to reset your password:\n$resetLink\n\n"
                . "This link will expire in 30 minutes.\n\n"
                . "If you did not request this, please ignore this email.";
            $htmlBody = '<p>Hello,</p>'
                . '<p>You requested a password reset for your AfamFresh account.</p>'
                . '<p><a href="' . $safeLink . '">Tap here to reset your password</a></p>'
                . '<p>If that does not open the app, copy this link into your phone:<br>'
                . '<code>' . $safeLink . '</code></p>'
                . '<p>This link will expire in 30 minutes.</p>'
                . '<p>If you did not request this, please ignore this email.</p>';

            $sent = sendEmailWithBrevo(
                $email,
                $user['fname'] ?? '',
                $subject,
                $htmlBody,
                $textBody
            );

            // Logged, never surfaced. The response stays an unconditional
            // success so it cannot be used to discover which addresses have
            // accounts — but a delivery failure has to be findable in the log,
            // because to the user it looks identical to never having asked.
            if (empty($sent['result'])) {
                error_log('[forgot_password] Reset email NOT delivered to ' . $email
                    . ' — ' . ($sent['message'] ?? 'unknown error'));
            }
        }

        echo json_encode(['success' => true]);
    } catch (PDOException $e) {
        error_log("Forgot password DB error: " . $e->getMessage());
        // Still report success — a DB error must not leak account existence either,
        // and the client has no useful action to take on a raw error here.
        echo json_encode(['success' => true]);
    }
    exit;
}

// ============================================================
// ACTION: RESET PASSWORD (WITH TOKEN)
// ============================================================
if ($action == 'reset_password') {
    $input = json_decode(file_get_contents('php://input'), true);
    if ($input !== null) {
        $token = trim($input['token'] ?? '');
        $newPassword = $input['password'] ?? '';
    } else {
        $token = trim($_POST['token'] ?? '');
        $newPassword = $_POST['password'] ?? '';
    }
    
    if (!$token || !$newPassword) {
        echo json_encode(['success' => false, 'message' => 'Token and new password required']);
        exit;
    }
    
    if (strlen($newPassword) < 6) {
        echo json_encode(['success' => false, 'message' => 'Password must be at least 6 characters']);
        exit;
    }

    // No email in this request (a reset token, not a login, identifies the
    // account) — IP-only bucket, same as the others.
    if (rateLimited($dbh, 'reset_password:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)) {
        failRateLimited();
    }

    try {
        $hashedToken = hash('sha256', $token);
        $stmt = $dbh->prepare("SELECT id FROM users WHERE reset_token = ? AND reset_token_expiry > NOW()");
        $stmt->execute([$hashedToken]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$user) {
            echo json_encode(['success' => false, 'message' => 'Link expired or already used']);
            exit;
        }
        
        $hashed = password_hash($newPassword, PASSWORD_DEFAULT);
        $update = $dbh->prepare("UPDATE users SET password = ?, reset_token = NULL, reset_token_expiry = NULL WHERE id = ?");
        $update->execute([$hashed, $user['id']]);
        
        echo json_encode(['success' => true, 'message' => 'Password reset successfully']);
    } catch (PDOException $e) {
        error_log("Reset password DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'message' => 'Database error']);
    }
    exit;
}

// ============================================================
// DEFAULT: INVALID ACTION
// ============================================================
echo json_encode(['success' => false, 'error' => 'Invalid action']);
exit;
?>