<?php
// =============================================================
// AfamFresh - profile management for the signed-in user
// =============================================================
// Actions: update | change_password | upload_avatar | remove_avatar
//          | notification_prefs
//
// Every action returns the FULL user object on success (except
// change_password, which has nothing to show), so the client can
// replace its cached copy atomically instead of patching fields
// and drifting out of sync.
//
// NOTE: deliberately no ini_set('display_errors', 1) here, unlike
// auth.php. A PHP notice printed before the JSON prepends HTML to
// the body and Gson then fails to parse the ENTIRE response — the
// symptom is a working endpoint that the app reports as broken.
// Errors belong in the log, not the payload.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/user_payload.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action  = $_GET['action'] ?? '';

// Accept a JSON body or form fields. auth.php uses JSON, notifications.php
// uses $_POST; this mirrors orders.php's getParam() so either works.
$jsonBody = json_decode(file_get_contents('php://input'), true);
if (!is_array($jsonBody)) { $jsonBody = []; }

function param($key, $default = null) {
    global $jsonBody;
    if (array_key_exists($key, $jsonBody))  return $jsonBody[$key];
    if (array_key_exists($key, $_POST))     return $_POST[$key];
    if (array_key_exists($key, $_GET))      return $_GET[$key];
    return $default;
}

function fail($message) {
    // Key is 'error', matching login/register/me. auth.php's password
    // actions use 'message' instead, which AuthRepository.resetPassword
    // misreads — not repeating that inconsistency here.
    echo json_encode(['success' => false, 'error' => $message]);
    exit;
}

function ok($dbh, $user_id, $extra = []) {
    echo json_encode(array_merge(
        ['success' => true, 'user' => buildUserPayload($dbh, $user_id)],
        $extra
    ));
    exit;
}

/** Current password hash, or null for Google accounts that never set one. */
function currentPasswordHash($dbh, $user_id) {
    $stmt = $dbh->prepare("SELECT password FROM users WHERE id = ?");
    $stmt->execute([$user_id]);
    $hash = $stmt->fetchColumn();
    return ($hash === false || $hash === null || $hash === '') ? null : $hash;
}

// =============================================================
// ACTION: UPDATE PROFILE
// =============================================================
if ($action === 'update') {
    $fname   = trim((string)param('fname', ''));
    $lname   = trim((string)param('lname', ''));
    $email   = trim((string)param('email', ''));
    $area    = trim((string)param('area', ''));
    $address = trim((string)param('address', ''));
    $mobileRaw = trim((string)param('mobile', ''));
    $currentPassword = (string)param('current_password', '');

    if ($fname === '' || mb_strlen($fname) > 100) fail('First name is required.');
    if ($lname === '' || mb_strlen($lname) > 50)  fail('Last name is required.');
    if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL) || mb_strlen($email) > 100) {
        fail('Enter a valid email address.');
    }
    // A delivery address is a CUSTOMER concept — it is where their order goes.
    // Riders and vendors have none, and requiring it meant a rider could not
    // save their own profile at all: those accounts are created with blank
    // area/address, so this rejected every update they made.
    require_once __DIR__ . '/../includes/account_type.php';
    if (accountTypeOf($dbh, $user_id) === 'customer') {
        if ($area === '' || mb_strlen($area) > 100)       fail('Delivery area is required.');
        if ($address === '' || mb_strlen($address) > 200) fail('Delivery address is required.');
    } else {
        if (mb_strlen($area) > 100)    fail('That area name is too long.');
        if (mb_strlen($address) > 200) fail('That address is too long.');
    }

    // Blank mobile must become NULL, never ''. `mobile` is UNIQUE, so a
    // second user saving an empty string collides with the first one on ''.
    $mobile = preg_replace('/[\s\-()]/', '', $mobileRaw);
    if ($mobile === '') {
        $mobile = null;
    } elseif (!preg_match('/^\+?[0-9]{7,15}$/', $mobile)) {
        fail('Enter a valid mobile number.');
    }

    $existing = $dbh->prepare("SELECT email FROM users WHERE id = ?");
    $existing->execute([$user_id]);
    $currentEmail = $existing->fetchColumn();
    $emailChanged = (strcasecmp($email, (string)$currentEmail) !== 0);

    if ($emailChanged) {
        $hash = currentPasswordHash($dbh, $user_id);
        if ($hash === null) {
            fail("This account signs in with Google, so its email address can't be changed here.");
        }
        if ($currentPassword === '') {
            fail('Enter your current password to change your email address.');
        }
        if (!password_verify($currentPassword, $hash)) {
            fail('That password is incorrect.');
        }
    }

    try {
        $dupe = $dbh->prepare("SELECT id FROM users WHERE email = ? AND id <> ?");
        $dupe->execute([$email, $user_id]);
        if ($dupe->fetch()) fail('That email is already used by another account.');

        if ($mobile !== null) {
            $dupe = $dbh->prepare("SELECT id FROM users WHERE mobile = ? AND id <> ?");
            $dupe->execute([$mobile, $user_id]);
            if ($dupe->fetch()) fail('That mobile number is already used by another account.');
        }

        $stmt = $dbh->prepare(
            "UPDATE users SET fname = ?, lname = ?, email = ?, mobile = ?, area = ?, address = ?
             WHERE id = ?"
        );
        $stmt->execute([$fname, $lname, $email, $mobile, $area, $address, $user_id]);
    } catch (PDOException $e) {
        // 23000 = integrity constraint. Reachable despite the checks above:
        // another request can take the address between the SELECT and the
        // UPDATE. Report the same friendly message rather than a 500.
        if ($e->getCode() === '23000') {
            error_log("Profile update duplicate for user $user_id: " . $e->getMessage());
            fail('That email or mobile number is already used by another account.');
        }
        error_log("Profile update DB error for user $user_id: " . $e->getMessage());
        fail('Could not save your changes. Please try again.');
    }

    // Keep the session's cached copies honest.
    $_SESSION['user_name']  = trim("$fname $lname");
    $_SESSION['user_email'] = $email;

    ok($dbh, $user_id);
}

// =============================================================
// ACTION: CHANGE PASSWORD
// =============================================================
if ($action === 'change_password') {
    $current = (string)param('current_password', '');
    $new     = (string)param('new_password', '');

    $hash = currentPasswordHash($dbh, $user_id);
    if ($hash === null) {
        // Genuinely actionable: reset_password sets the column unconditionally,
        // so the forgot-password flow does work for a Google account.
        fail('Your account signs in with Google and has no password yet. Use "Forgot password" on the sign-in screen to set one.');
    }
    if ($current === '' || !password_verify($current, $hash)) {
        fail('Your current password is incorrect.');
    }
    // Same minimum as auth.php's reset_password, so the two flows agree.
    if (strlen($new) < 6) {
        fail('Password must be at least 6 characters');
    }
    if ($new === $current) {
        fail('Choose a password different from your current one.');
    }

    try {
        $stmt = $dbh->prepare(
            "UPDATE users SET password = ?, reset_token = NULL, reset_token_expiry = NULL WHERE id = ?"
        );
        $stmt->execute([password_hash($new, PASSWORD_DEFAULT), $user_id]);
    } catch (PDOException $e) {
        error_log("Change password DB error for user $user_id: " . $e->getMessage());
        fail('Could not change your password. Please try again.');
    }

    // The session is intentionally left alive — the user stays signed in on
    // this device. Other devices cannot be invalidated (sessions are plain
    // PHP files with no registry), so no promise about that is made.
    echo json_encode(['success' => true]);
    exit;
}

// =============================================================
// ACTION: UPLOAD AVATAR  (multipart, field name "avatar")
// =============================================================
if ($action === 'upload_avatar') {
    if (!isset($_FILES['avatar'])) fail('No image was received.');

    $err = $_FILES['avatar']['error'];
    if ($err === UPLOAD_ERR_INI_SIZE || $err === UPLOAD_ERR_FORM_SIZE) {
        fail('That image is too large. Please pick one under 5 MB.');
    }
    if ($err !== UPLOAD_ERR_OK) {
        error_log("Avatar upload error code $err for user $user_id");
        fail('Upload failed. Please try again.');
    }
    if ($_FILES['avatar']['size'] > 5 * 1024 * 1024) {
        fail('That image is too large. Please pick one under 5 MB.');
    }

    $tmp = $_FILES['avatar']['tmp_name'];

    // Sniff the type server-side. admin/add-product.php takes the extension
    // straight from the client-supplied filename with no check, which lets a
    // .php land in a web-served directory. The stored extension here comes
    // only from the sniffed MIME — never from the uploaded name.
    $allowed = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'];
    $finfo = finfo_open(FILEINFO_MIME_TYPE);
    $mime  = finfo_file($finfo, $tmp);
    finfo_close($finfo);

    if (!isset($allowed[$mime]) || getimagesize($tmp) === false) {
        fail('Only JPG, PNG or WebP images are supported.');
    }
    $ext = $allowed[$mime];

    $dir = __DIR__ . '/../uploads/avatars';
    if (!is_dir($dir) && !@mkdir($dir, 0775, true)) {
        error_log("Avatar dir missing and not creatable: $dir");
        fail("Couldn't save the image. Please try again.");
    }

    // Random, namespaced, collision-proof. add-product.php uses time().$ext,
    // which collides for two uploads in the same second.
    $filename = 'avatar_' . $user_id . '_' . bin2hex(random_bytes(8)) . '.' . $ext;

    if (!move_uploaded_file($tmp, $dir . '/' . $filename)) {
        // Most likely cause: the directory is not writable by Apache's user.
        error_log("move_uploaded_file failed to $dir/$filename (permissions?)");
        fail("Couldn't save the image. Please try again.");
    }

    // Remove the previous file, but only when it matches a name this endpoint
    // generated — never unlink an arbitrary string out of the database.
    $prevStmt = $dbh->prepare("SELECT profile_picture FROM users WHERE id = ?");
    $prevStmt->execute([$user_id]);
    $prev = $prevStmt->fetchColumn();
    if ($prev && preg_match('/^avatar_\d+_[0-9a-f]{16}\.(jpg|png|webp)$/', $prev)) {
        @unlink($dir . '/' . $prev);
    }

    $stmt = $dbh->prepare("UPDATE users SET profile_picture = ? WHERE id = ?");
    $stmt->execute([$filename, $user_id]);

    ok($dbh, $user_id);
}

// =============================================================
// ACTION: REMOVE AVATAR
// =============================================================
if ($action === 'remove_avatar') {
    $prevStmt = $dbh->prepare("SELECT profile_picture FROM users WHERE id = ?");
    $prevStmt->execute([$user_id]);
    $prev = $prevStmt->fetchColumn();
    if ($prev && preg_match('/^avatar_\d+_[0-9a-f]{16}\.(jpg|png|webp)$/', $prev)) {
        @unlink(__DIR__ . '/../uploads/avatars/' . $prev);
    }

    $stmt = $dbh->prepare("UPDATE users SET profile_picture = NULL WHERE id = ?");
    $stmt->execute([$user_id]);

    // avatar_url now falls back to the Google photo, if there is one.
    ok($dbh, $user_id);
}

// =============================================================
// ACTION: NOTIFICATION PREFERENCES
// =============================================================
if ($action === 'notification_prefs') {
    // Shape fixed by includes/classes/NotificationManager.php, which is the
    // code that actually consults this: {"email":bool,"push":bool}
    $emailOn = filter_var(param('email', true), FILTER_VALIDATE_BOOLEAN);
    $pushOn  = filter_var(param('push',  true), FILTER_VALIDATE_BOOLEAN);

    $stmt = $dbh->prepare("UPDATE users SET notification_preferences = ? WHERE id = ?");
    $stmt->execute([json_encode(['email' => $emailOn, 'push' => $pushOn]), $user_id]);

    ok($dbh, $user_id);
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);