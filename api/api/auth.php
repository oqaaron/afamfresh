<?php
// No display_errors here. admin/includes/config.php sets it from APP_ENV a few
// lines below, and forcing it on first meant anything that went wrong before
// that require -- in session_start(), or while env.php was loading -- printed
// its file paths into the response of an endpoint anyone can reach without
// logging in. profile.php, rider.php and roles.php each carry a note saying
// they deliberately leave this alone; this file was the last one that didn't.
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

// Every place in this file that accepts a new password measures it against
// this one value. Registration and reset_password each used to decide for
// themselves, and they disagreed -- reset required 6 characters while
// register required nothing at all.
const MIN_PASSWORD_LENGTH = 6;

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
// ACTION: REGISTER RIDER
// ============================================================
// Rider accounts are provisioned as rider accounts in one transaction. This
// avoids creating a customer account first and then leaving the applicant
// blocked by rider.php while an administrator repairs the role manually.
if ($action == 'register_rider') {
    $input = json_decode(file_get_contents('php://input'), true) ?: [];
    $fname = trim($input['fname'] ?? '');
    $lname = trim($input['lname'] ?? '');
    $email = strtolower(trim($input['email'] ?? ''));
    $phone = trim($input['phone'] ?? '');
    $password = $input['password'] ?? '';
    $vehicleType = trim($input['vehicle_type'] ?? 'motorcycle');
    $vehiclePlate = trim($input['vehicle_plate'] ?? '');

    if ($fname === '' || $email === '' || $phone === '' || strlen($password) < MIN_PASSWORD_LENGTH
        || $vehiclePlate === '') {
        http_response_code(400);
        echo json_encode(['success' => false, 'error' => 'Please fill all required fields.']);
        exit;
    }

    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        http_response_code(400);
        echo json_encode(['success' => false, 'error' => 'Please enter a valid email address.']);
        exit;
    }

    if (rateLimited($dbh, 'register_rider:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)
        || rateLimited($dbh, 'register_rider:id:' . $email, 5, 300)) {
        failRateLimited();
    }

    try {
        $check = $dbh->prepare("SELECT id FROM users WHERE email = ?");
        $check->execute([$email]);
        if ($check->fetchColumn()) {
            http_response_code(409);
            echo json_encode(['success' => false, 'error' => 'An account with this email already exists.']);
            exit;
        }

        $dbh->beginTransaction();
        $hash = password_hash($password, PASSWORD_DEFAULT);
        $userInsert = $dbh->prepare(
            "INSERT INTO users (fname, lname, email, mobile, password, area, address, account_type, `current_role`)
             VALUES (?, ?, ?, ?, ?, 'Not specified', 'Not specified', 'rider', 'rider')"
        );
        $userInsert->execute([$fname, $lname, $email, $phone, $hash]);
        $userId = (int)$dbh->lastInsertId();

        // riders.password is legacy NOT NULL; authentication uses users.password.
        $riderInsert = $dbh->prepare(
            "INSERT INTO riders (user_id, name, phone, email, password, vehicle_type, vehicle_plate, status, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, 'offline', NOW())"
        );
        $riderInsert->execute([
            $userId, trim($fname . ' ' . $lname), $phone, $email, $hash, $vehicleType, $vehiclePlate
        ]);
        $dbh->commit();

        echo json_encode([
            'success' => true,
            'message' => 'Rider registration submitted successfully. You can now log in.'
        ]);
    } catch (Throwable $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('register_rider failed: ' . $e->getMessage());
        http_response_code(500);
        echo json_encode(['success' => false, 'error' => 'Registration failed. Please try again.']);
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

    // The password was going straight into password_hash() with nothing
    // checked, so '' was a valid choice and hashed to a perfectly usable
    // credential. reset_password has always enforced a floor -- meaning an
    // account could be created with no password at all and only acquire a
    // real one if its owner happened to run a reset. Same floor, same
    // constant, both ends.
    if (strlen($password) < MIN_PASSWORD_LENGTH) {
        echo json_encode([
            'success' => false,
            'error' => 'Password must be at least ' . MIN_PASSWORD_LENGTH . ' characters'
        ]);
        exit;
    }

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
    
    // --- Security Check: the email must be one Google has verified ---
    //
    // The lookup below is `WHERE google_id = ? OR email = ?`, so a token whose
    // email matches an existing account links to it and logs straight in. That
    // is the intended behaviour for someone who registered with a password and
    // later uses the Google button — but it means the email address alone is
    // enough to take over an account, so Google has to have proved the holder
    // owns it.
    //
    // email_verified is NOT always true. Google Workspace domains and some
    // federated setups issue tokens with it false, and an attacker who can set
    // an arbitrary unverified address on an account they control could
    // otherwise claim any user here by their email address.
    //
    // tokeninfo returns this as the STRING "true", not a boolean — a truthy
    // check like `if (!$userInfo['email_verified'])` passes on the string
    // "false", which is the whole vulnerability restated. Compare exactly.
    //
    // Absent is treated as unverified: this endpoint refuses what it cannot
    // confirm rather than assuming Google meant to vouch for it.
    if (($userInfo['email_verified'] ?? 'false') !== 'true') {
        error_log("Google Sign-In: refusing unverified email $email (email_verified="
            . var_export($userInfo['email_verified'] ?? null, true) . ")");
        echo json_encode([
            'success' => false,
            'error'   => 'Your Google account email is not verified. Verify it with Google, then try again.',
        ]);
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
// ACTION: SEND PHONE OTP
// ============================================================
// Third sign-in mechanism alongside password and Google. Reuses
// normaliseUgandanMsisdn() and sendSmsWithBrevo() from includes/brevo-sms.php
// — the SMS sending path this app already has working for order
// notifications — rather than standing up a second one via the Twilio helper
// that has never had a caller.
if ($action == 'send_phone_otp') {
    require_once __DIR__ . '/../includes/brevo-sms.php';

    $input = json_decode(file_get_contents('php://input'), true);
    $mobileRaw = trim($input['mobile'] ?? $_POST['mobile'] ?? '');

    $mobile = normaliseUgandanMsisdn($mobileRaw);
    if ($mobile === null) {
        echo json_encode(['success' => false, 'error' => 'Enter a valid mobile number.']);
        exit;
    }

    // Tighter than login/register's 5-per-5-minutes: each of these sends
    // costs real SMS credit, where a failed password guess costs nothing.
    // Bucketed by mobile AND by IP, same reasoning as every other action
    // here — one number getting bombed, and one IP working through many
    // numbers, are different attacks and both need catching.
    if (rateLimited($dbh, 'phone_otp:mobile:' . $mobile, 3, 300)
        || rateLimited($dbh, 'phone_otp:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 10, 300)) {
        failRateLimited();
    }

    try {
        $code = str_pad((string)random_int(0, 999999), 6, '0', STR_PAD_LEFT);
        $codeHash = password_hash($code, PASSWORD_DEFAULT);
        $expiresAt = date('Y-m-d H:i:s', time() + 600); // 10 minutes

        // Sent before the row is written, not after, so status/last_error
        // reflect the real outcome of this attempt in a single write rather
        // than an insert-as-pending followed by a second update.
        $sendResult = sendSmsWithBrevo(
            $mobile,
            "Your AfamFresh verification code is $code. It expires in 10 minutes."
        );

        // UNIQUE KEY (mobile, purpose) means a second request for the same
        // number upserts the existing row rather than colliding with it.
        // Every column resets to describe THIS attempt, not the previous
        // one — including clearing proof_token/verified_at, so a stale
        // verified state from an earlier code can't leak into a fresh send.
        // provider_response is intentionally left out here: sendSmsWithBrevo
        // returns success/error, not a captured raw response body, so
        // there's nothing honest to put in that column yet.
        $stmt = $dbh->prepare(
            "INSERT INTO user_otp_verifications
                (mobile, purpose, otp_hash, status, sent_count, last_sent_at, last_error,
                 expires_at, verified_at, attempt_count, proof_token)
             VALUES
                (?, 'signup', ?, ?, 1, NOW(), ?, ?, NULL, 0, NULL)
             ON DUPLICATE KEY UPDATE
                otp_hash = VALUES(otp_hash),
                status = VALUES(status),
                sent_count = sent_count + 1,
                last_sent_at = NOW(),
                last_error = VALUES(last_error),
                expires_at = VALUES(expires_at),
                verified_at = NULL,
                attempt_count = 0,
                proof_token = NULL"
        );
        $stmt->execute([
            $mobile,
            $codeHash,
            $sendResult['success'] ? 'sent' : 'failed',
            $sendResult['error'] ?? null,
            $expiresAt,
        ]);

        if (!$sendResult['success']) {
            echo json_encode([
                'success' => false,
                'error' => $sendResult['error'] ?? 'Could not send the verification code.',
            ]);
            exit;
        }

        echo json_encode(['success' => true]);
    } catch (PDOException $e) {
        error_log("send_phone_otp DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not send the verification code.']);
    }
    exit;
}

// ============================================================
// ACTION: VERIFY PHONE OTP
// ============================================================
if ($action == 'verify_phone_otp') {
    require_once __DIR__ . '/../includes/brevo-sms.php';

    $input = json_decode(file_get_contents('php://input'), true);
    $mobileRaw = trim($input['mobile'] ?? $_POST['mobile'] ?? '');
    $code = trim($input['code'] ?? $_POST['code'] ?? '');
    $appType = accountTypeForAppRole($input['app_role'] ?? $_POST['app_role'] ?? 'customer') ?? 'customer';

    $mobile = normaliseUgandanMsisdn($mobileRaw);
    if ($mobile === null || $code === '') {
        echo json_encode(['success' => false, 'error' => 'Enter the code that was sent to you.']);
        exit;
    }

    try {
        $stmt = $dbh->prepare(
            "SELECT * FROM user_otp_verifications WHERE mobile = ? AND purpose = 'signup'"
        );
        $stmt->execute([$mobile]);
        $verification = $stmt->fetch(PDO::FETCH_ASSOC);

        $isExpired = $verification && strtotime($verification['expires_at']) < time();
        if (!$verification || $verification['status'] === 'used' || $isExpired) {
            if ($isExpired && $verification['status'] !== 'expired') {
                $dbh->prepare("UPDATE user_otp_verifications SET status = 'expired' WHERE id = ?")
                    ->execute([$verification['id']]);
            }
            echo json_encode(['success' => false, 'error' => 'That code has expired. Please request a new one.']);
            exit;
        }

        // A 6-digit code is only ~1M possibilities — brute-forceable without
        // a server-side attempt cap, unlike a real password.
        if ((int)$verification['attempt_count'] >= 5) {
            echo json_encode(['success' => false, 'error' => 'Too many incorrect attempts. Please request a new code.']);
            exit;
        }

        if (!password_verify($code, $verification['otp_hash'])) {
            $dbh->prepare("UPDATE user_otp_verifications SET attempt_count = attempt_count + 1 WHERE id = ?")
                ->execute([$verification['id']]);
            echo json_encode(['success' => false, 'error' => 'Incorrect code. Please try again.']);
            exit;
        }

        // Correct code. An account with this number already existing means
        // this is a login; otherwise it's the first half of a signup.
        $userStmt = $dbh->prepare("SELECT * FROM users WHERE mobile = ?");
        $userStmt->execute([$mobile]);
        $user = $userStmt->fetch(PDO::FETCH_ASSOC);

        if ($user) {
            // Same "right account, wrong app" guard login/google_login use —
            // without it, verifying a rider's number in the Customer app
            // would log the rider's account straight in here.
            if ($user['account_type'] !== $appType) {
                echo json_encode([
                    'success' => false,
                    'error'   => 'This is ' . accountTypeLabel($user['account_type'])
                               . ", so it can't be used in this app. "
                               . 'Please use the AfamFresh app for that account.',
                ]);
                exit;
            }

            // 'used', not 'verified' — this row's job is fully done in this
            // one request (an existing account was logged into). Contrast
            // with the new-number branch below, which stops at 'verified'
            // because a second request still has to happen.
            $dbh->prepare(
                "UPDATE user_otp_verifications SET status = 'used', verified_at = NOW() WHERE id = ?"
            )->execute([$verification['id']]);

            $token = generateToken();
            session_regenerate_id(true);
            $_SESSION['user_id'] = $user['id'];
            $_SESSION['user_name'] = $user['fname'] . ' ' . $user['lname'];
            $_SESSION['user_email'] = $user['email'];
            $_SESSION['auth_token'] = $token;

            echo json_encode([
                'success' => true,
                'is_new_user' => false,
                'token' => $token,
                'user' => buildUserPayload($dbh, $user['id']),
            ]);
        } else {
            // No account yet. users.fname and users.lname are NOT NULL and
            // this endpoint has no name to put in them, so the account isn't
            // created here — status stops at 'verified', and
            // complete_phone_signup (below) is what advances it to 'used'
            // once it actually has a name to work with.
            //
            // proof_token — added on top of the original design, which had
            // no column for this — is what stops complete_phone_signup being
            // callable by anyone who simply knows a number currently sitting
            // at status = 'verified'. Knowing the number isn't proof you're
            // the one who received the code.
            $proofToken = bin2hex(random_bytes(16));
            $dbh->prepare(
                "UPDATE user_otp_verifications
                    SET status = 'verified', verified_at = NOW(), proof_token = ?, expires_at = ?
                  WHERE id = ?"
            )->execute([$proofToken, date('Y-m-d H:i:s', time() + 900), $verification['id']]); // 15 minutes to finish signup

            echo json_encode([
                'success' => true,
                'is_new_user' => true,
                'mobile' => $mobile,
                'proof_token' => $proofToken,
            ]);
        }
    } catch (PDOException $e) {
        error_log("verify_phone_otp DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Something went wrong. Please try again.']);
    }
    exit;
}

// ============================================================
// ACTION: COMPLETE PHONE SIGNUP
// ============================================================
// The second half of a new-number signup — only reachable with a
// proof_token verify_phone_otp just issued, so this can't be called for a
// phone number nobody actually verified.
if ($action == 'complete_phone_signup') {
    require_once __DIR__ . '/../includes/brevo-sms.php';

    $input = json_decode(file_get_contents('php://input'), true);
    $mobileRaw = trim($input['mobile'] ?? $_POST['mobile'] ?? '');
    $proofToken = trim($input['proof_token'] ?? $_POST['proof_token'] ?? '');
    $fname = trim($input['fname'] ?? $_POST['fname'] ?? '');
    $lname = trim($input['lname'] ?? $_POST['lname'] ?? '');
    $appType = accountTypeForAppRole($input['app_role'] ?? $_POST['app_role'] ?? 'customer') ?? 'customer';

    $mobile = normaliseUgandanMsisdn($mobileRaw);
    if ($mobile === null || $proofToken === '') {
        echo json_encode(['success' => false, 'error' => 'Verification expired. Please start again.']);
        exit;
    }
    if ($fname === '' || $lname === '') {
        echo json_encode(['success' => false, 'error' => 'Enter your first and last name.']);
        exit;
    }

    try {
        $stmt = $dbh->prepare(
            "SELECT * FROM user_otp_verifications
              WHERE mobile = ? AND purpose = 'signup' AND proof_token = ? AND status = 'verified'"
        );
        $stmt->execute([$mobile, $proofToken]);
        $verification = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$verification || strtotime($verification['expires_at']) < time()) {
            echo json_encode(['success' => false, 'error' => 'Verification expired. Please start again.']);
            exit;
        }

        // Someone could have registered this number by another route
        // (password registration or Google Sign-In both accept mobile as a
        // profile field) between verify and now — re-check rather than
        // trust the lookup verify_phone_otp already did.
        $dupe = $dbh->prepare("SELECT id FROM users WHERE mobile = ?");
        $dupe->execute([$mobile]);
        if ($dupe->fetch()) {
            echo json_encode(['success' => false, 'error' => 'That number is already registered. Please log in instead.']);
            exit;
        }

        // Same 'Not specified' placeholders register and google_login use
        // for fields this flow doesn't collect yet — filled in later from
        // the profile screen, same as every other account.
        $insertStmt = $dbh->prepare(
            "INSERT INTO users (fname, lname, mobile, area, address, account_type, `current_role`)
             VALUES (?, ?, ?, 'Not specified', 'Not specified', ?, ?)"
        );
        $result = $insertStmt->execute([
            $fname, $lname, $mobile,
            $appType,
            $appType === 'customer' ? 'user' : $appType,
        ]);

        if (!$result) {
            $errorInfo = $insertStmt->errorInfo();
            error_log("complete_phone_signup INSERT failed: " . print_r($errorInfo, true));
            echo json_encode(['success' => false, 'error' => 'Could not create your account.']);
            exit;
        }

        $userId = $dbh->lastInsertId();

        // Same pattern as register/google_login: a customer is usable
        // immediately, a rider or vendor needs admin approval first.
        if ($appType === 'customer') {
            $dbh->prepare(
                "INSERT INTO user_roles (user_id, role, status) VALUES (?, 'user', 'active')
                 ON DUPLICATE KEY UPDATE status = 'active'"
            )->execute([$userId]);
        }

        require_once __DIR__ . '/../includes/notifications.php';
        notifyWelcome($userId, $fname, $appType);

        // Row's job is fully done — account created and about to be logged
        // into. 'used' closes out the lifecycle this table's status column
        // tracks; unlike the phone_verifications table this replaced, rows
        // here are a permanent record of every OTP ever issued, not
        // transient working memory to delete once spent.
        $dbh->prepare("UPDATE user_otp_verifications SET status = 'used' WHERE id = ?")
            ->execute([$verification['id']]);

        $token = generateToken();
        session_regenerate_id(true);
        $_SESSION['user_id'] = $userId;
        $_SESSION['user_name'] = $fname . ' ' . $lname;
        // No email collected by this flow — NULL here matches the column
        // (nullable) and buildUserPayload's own handling of a Google-only
        // account, which has no email-backed session value either.
        $_SESSION['user_email'] = null;
        $_SESSION['auth_token'] = $token;

        echo json_encode([
            'success' => true,
            'token' => $token,
            'user' => buildUserPayload($dbh, $userId),
        ]);
    } catch (PDOException $e) {
        error_log("complete_phone_signup DB error: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not create your account.']);
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

    // Unknown addresses still receive the same success response. For a known
    // address, however, a provider failure must not be reported as success or
    // the customer is told to check an inbox that can never receive anything.
    try {
        // fname is selected purely so the email can address the recipient;
        // nothing downstream branches on it. (The column is fname/lname —
        // there is no `name` column on users.)
        $stmt = $dbh->prepare("SELECT id, fname FROM users WHERE email = ?");
        $stmt->execute([$email]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);

        $deliveryFailed = false;
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

            // An https link, on a per-app path the corresponding flavor
            // registers as a verified Android App Link (see AndroidManifest.xml
            // and api/.htaccess's /go/<scheme>/reset-password rewrite) — not
            // the raw afamfresh:// scheme.
            //
            // The custom scheme was both a spam signal (these emails were
            // landing in spam) and frequently dead: many mail clients will not
            // linkify a non-HTTP href, and some strip it. Now, on a device with
            // the matching app installed, Android opens the app directly for
            // this URL with no browser hop at all — and a plain https URL is
            // also the only shape Digital Asset Links verification can bind to
            // a specific app, closing the old gap where any app claiming the
            // same custom scheme could intercept the token. reset-password.php
            // (reached via the /go/.../reset-password rewrite, which sets
            // ?scheme=) still exists to hand off manually and explain itself
            // when the app is missing or unverified.
            require_once __DIR__ . '/../includes/user_payload.php';
            $resetLink = appBaseUrl() . '/go/' . $scheme . '/reset-password?token=' . rawurlencode($rawToken);
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
                $deliveryFailed = true;
                error_log('[forgot_password] Reset email NOT delivered to ' . $email
                    . ' — ' . ($sent['message'] ?? 'unknown error'));
            }
        }

        echo json_encode($deliveryFailed
            ? ['success' => false, 'error' => 'We could not send the reset email right now. Please try again later.']
            : ['success' => true]);
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
    
    if (strlen($newPassword) < MIN_PASSWORD_LENGTH) {
        echo json_encode([
            'success' => false,
            'message' => 'Password must be at least ' . MIN_PASSWORD_LENGTH . ' characters'
        ]);
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