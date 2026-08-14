<?php
require_once __DIR__ . '/../admin/includes/config.php';
require_once __DIR__ . '/notifications.php';

/**
 * Add a role to a user
 * @param int $userId - User ID
 * @param string $role - Role (user, vendor, rider, wholesaler)
 * @param string $status - Status (active, pending, suspended)
 * @return bool|string - Success status or error message
 */
function addRoleToUser($userId, $role, $status = 'pending') {
    global $dbh;
    
    // Validate role
    $allowedRoles = ['user', 'vendor', 'rider', 'wholesaler'];
    if (!in_array($role, $allowedRoles)) {
        return "Invalid role specified.";
    }
    
    // Check if user already has an extra role (if trying to add vendor, rider, or wholesaler)
    if ($role !== 'user') {
        $extraRoles = getActiveExtraRoles($userId);
        if (count($extraRoles) >= 1) {
            return "You can only have one additional role (Vendor, Rider, or Wholesaler). You already have: " . ucfirst($extraRoles[0]);
        }
    }
    
    try {
        $sql = "INSERT INTO user_roles (user_id, role, status) VALUES (:user_id, :role, :status)";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':role', $role, PDO::PARAM_STR);
        $query->bindParam(':status', $status, PDO::PARAM_STR);
        $query->execute();
        return true;
    } catch (PDOException $e) {
        error_log("Add role error: " . $e->getMessage());
        return "Database error occurred.";
    }
}

/**
 * Get active extra roles (non-user roles) for a user
 * @param int $userId - User ID
 * @return array - Active extra roles
 */
function getActiveExtraRoles($userId) {
    global $dbh;
    try {
        $sql = "SELECT role FROM user_roles WHERE user_id = :user_id AND role != 'user' AND status = 'active'";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->execute();
        $roles = $query->fetchAll(PDO::FETCH_ASSOC);
        return array_column($roles, 'role');
    } catch (PDOException $e) {
        error_log("Get active extra roles error: " . $e->getMessage());
        return [];
    }
}

/**
 * Get all roles for a user
 * @param int $userId - User ID
 * @return array - User's roles
 */
function getUserRoles($userId) {
    global $dbh;
    try {
        $sql = "SELECT role, status FROM user_roles WHERE user_id = :user_id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->execute();
        return $query->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log("Get user roles error: " . $e->getMessage());
        return [];
    }
}

/**
 * Check if user has a specific role
 * @param int $userId - User ID
 * @param string $role - Role to check
 * @return bool - Whether user has the role
 */
function userHasRole($userId, $role) {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM user_roles WHERE user_id = :user_id AND role = :role AND status = 'active'";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':role', $role, PDO::PARAM_STR);
        $query->execute();
        $result = $query->fetch(PDO::FETCH_ASSOC);
        return (int)$result['count'] > 0;
    } catch (PDOException $e) {
        error_log("Check user role error: " . $e->getMessage());
        return false;
    }
}

/**
 * Get active roles for a user
 * @param int $userId - User ID
 * @return array - Active roles
 */
function getActiveRoles($userId) {
    global $dbh;
    try {
        $sql = "SELECT role FROM user_roles WHERE user_id = :user_id AND status = 'active'";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->execute();
        $roles = $query->fetchAll(PDO::FETCH_ASSOC);
        return array_column($roles, 'role');
    } catch (PDOException $e) {
        error_log("Get active roles error: " . $e->getMessage());
        return [];
    }
}

/**
 * Update user's current role
 * @param int $userId - User ID
 * @param string $role - New current role
 * @return bool - Success status
 */
function setCurrentRole($userId, $role) {
    global $dbh;
    
    // Verify user has this role before switching
    if (!userHasRole($userId, $role)) {
        return false;
    }
    
    try {
        $sql = "UPDATE users SET `current_role` = :role WHERE id = :user_id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':role', $role, PDO::PARAM_STR);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Set current role error: " . $e->getMessage());
        return false;
    }
}

/**
 * Update role status
 * @param int $userId - User ID
 * @param string $role - Role to update
 * @param string $status - New status
 * @return bool - Success status
 */
function updateRoleStatus($userId, $role, $status) {
    global $dbh;
    try {
        $sql = "UPDATE user_roles SET status = :status WHERE user_id = :user_id AND role = :role";
        $query = $dbh->prepare($sql);
        $query->bindParam(':status', $status, PDO::PARAM_STR);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':role', $role, PDO::PARAM_STR);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Update role status error: " . $e->getMessage());
        return false;
    }
}

/**
 * Remove a role from a user
 * @param int $userId - User ID
 * @param string $role - Role to remove
 * @return bool - Success status
 */
function removeRoleFromUser($userId, $role) {
    global $dbh;
    
    // Prevent removing the base 'user' role
    if ($role === 'user') {
        return false;
    }
    
    try {
        $sql = "DELETE FROM user_roles WHERE user_id = :user_id AND role = :role";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':role', $role, PDO::PARAM_STR);
        $result = $query->execute();
        
        // If the removed role was the current role, switch back to 'user'
        if ($result) {
            // Backticked deliberately: MariaDB parses a bare `current_role` in
            // a SELECT list as the built-in CURRENT_ROLE() function, which
            // returns NULL for a normal connection instead of reading the
            // column. Unquoted, the comparison below never matched and a
            // revoked role was never reset to 'user'.
            $currentRoleStmt = $dbh->prepare("SELECT `current_role` FROM users WHERE id = :user_id");
            $currentRoleStmt->bindParam(':user_id', $userId, PDO::PARAM_INT);
            $currentRoleStmt->execute();
            $currentRole = $currentRoleStmt->fetchColumn();
            
            if ($currentRole === $role) {
                setCurrentRole($userId, 'user');
            }
        }
        
        return $result;
    } catch (PDOException $e) {
        error_log("Remove role error: " . $e->getMessage());
        return false;
    }
}

/**
 * Check if user has multiple roles
 * @param int $userId - User ID
 * @return bool - Whether user has multiple active roles (including 'user')
 */
function hasMultipleRoles($userId) {
    $activeRoles = getActiveRoles($userId);
    return count($activeRoles) > 1;
}

/**
 * Check if user can request a new role
 * @param int $userId - User ID
 * @param string $requestedRole - The role being requested
 * @return bool|string - True if allowed, error message if not
 */
function canRequestRole($userId, $requestedRole) {
    // User cannot request the 'user' role as it's the base role
    if ($requestedRole === 'user') {
        return "Cannot request the base user role.";
    }
    
    // Check if user already has this role
    if (userHasRole($userId, $requestedRole)) {
        return "You already have the {$requestedRole} role.";
    }
    
    // Check if user already has a pending request for this role
    if (hasPendingRoleRequest($userId, $requestedRole)) {
        return "You already have a pending request for {$requestedRole}.";
    }
    
    // Check if user already has any extra role
    $extraRoles = getActiveExtraRoles($userId);
    if (count($extraRoles) >= 1) {
        $existingRole = ucfirst($extraRoles[0]);
        return "You can only have one additional role. You are already a {$existingRole}. To become a " . ucfirst($requestedRole) . ", please contact support to change your role.";
    }
    
    return true;
}

/**
 * Submit a role request with validation
 */
function submitRoleRequest($userId, $requestedRole) {
    global $dbh;
    
    // Validate the request
    $canRequest = canRequestRole($userId, $requestedRole);
    if ($canRequest !== true) {
        error_log("Role request denied for user {$userId}: {$canRequest}");
        return false;
    }
    
    try {
        $sql = "INSERT INTO role_requests (user_id, requested_role, status) VALUES (?, ?, 'pending')
                ON DUPLICATE KEY UPDATE status = 'pending', created_at = NOW()";
        $stmt = $dbh->prepare($sql);
        return $stmt->execute([$userId, $requestedRole]);
    } catch (PDOException $e) {
        error_log("Role request error: " . $e->getMessage());
        return false;
    }
}

/**
 * Check if user has a pending request for a role
 */
function hasPendingRoleRequest($userId, $role) {
    global $dbh;
    $stmt = $dbh->prepare("SELECT id FROM role_requests WHERE user_id = ? AND requested_role = ? AND status = 'pending'");
    $stmt->execute([$userId, $role]);
    return $stmt->fetch() !== false;
}

/**
 * Get all role requests for a user
 */
function getUserRoleRequests($userId) {
    global $dbh;
    $stmt = $dbh->prepare("SELECT * FROM role_requests WHERE user_id = ? ORDER BY created_at DESC");
    $stmt->execute([$userId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

/**
 * Creates the working record a granted role needs, if not already present.
 *
 * `user_roles` says what an account is ALLOWED to do; `riders` / `vendors` hold
 * the working profile the corresponding app actually reads. Both are required:
 * api/rider.php resolves $_SESSION['user_id'] -> riders.user_id, and
 * api/vendor-profile.php does the same for vendors. Granting the role alone
 * leaves an approved user staring at "this account is not set up as a rider".
 *
 * Details are derived from the account, since a self-registered rider has not
 * supplied business details yet; an admin corrects them afterwards on
 * admin/edit-rider.php or the vendor pages.
 *
 * Idempotent: re-approving an account that already has a record is a no-op
 * rather than a duplicate-key failure.
 *
 * @return bool|string true, or a message describing what stopped it.
 */
function provisionRoleRecord($userId, $role) {
    global $dbh;

    if ($role !== 'rider' && $role !== 'vendor') {
        // 'user' needs nothing, and wholesaler has no table of its own yet.
        return true;
    }

    try {
        $u = $dbh->prepare("SELECT fname, lname, email, mobile, area FROM users WHERE id = ?");
        $u->execute([$userId]);
        $user = $u->fetch(PDO::FETCH_ASSOC);
        if (!$user) {
            return "That user account no longer exists.";
        }

        $name = trim($user['fname'] . ' ' . $user['lname']);
        // riders.phone and vendors.phone are both NOT NULL while users.mobile
        // is nullable, so a missing number becomes '' rather than failing.
        $phone = $user['mobile'] ?? '';
        // 'Not specified' is the sentinel registration writes into area; storing
        // it as a location would show customers a place that does not exist.
        $area = (($user['area'] ?? '') === 'Not specified') ? null : ($user['area'] ?: null);

        if ($role === 'rider') {
            $exists = $dbh->prepare("SELECT id FROM riders WHERE user_id = ?");
            $exists->execute([$userId]);
            if ($exists->fetchColumn()) {
                return true;
            }
            // riders.password is NOT NULL but unused for app sign-in, which
            // goes through the user account. Stored as an unusable random hash
            // rather than a blank that might later read as a valid empty
            // password.
            $ins = $dbh->prepare(
                "INSERT INTO riders (user_id, name, phone, email, password, status)
                 VALUES (?, ?, ?, ?, ?, 'offline')"
            );
            $ins->execute([
                $userId, $name, $phone, $user['email'],
                password_hash(bin2hex(random_bytes(16)), PASSWORD_DEFAULT),
            ]);
            return true;
        }

        $exists = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
        $exists->execute([$userId]);
        if ($exists->fetchColumn()) {
            return true;
        }
        // is_verified starts at 0, and that is the whole point of this row.
        //
        // It used to be created with is_verified = 1, which collapsed a
        // three-stage flow into one: approving the role instantly published a
        // vendor whose business_name was just the person's own name and whose
        // phone was blank, because those are all provisioning can infer from a
        // user account. Nobody ever saw the verification step.
        //
        // The stages are: admin approves the role (this record appears,
        // unverified) -> the vendor fills in their real business details ->
        // an admin verifies. api/surplus-listings.php already refuses to
        // create a listing unless is_verified is TRUE, so the gate was there
        // all along with nothing behind it.
        $ins = $dbh->prepare(
            "INSERT INTO vendors (user_id, business_name, business_type, phone, email, location, is_verified, verification_date)
             VALUES (?, ?, 'market_vendor', ?, ?, ?, 0, NULL)"
        );
        $ins->execute([$userId, $name, $phone, $user['email'], $area]);
        return true;
    } catch (PDOException $e) {
        error_log("provisionRoleRecord($userId, $role) failed: " . $e->getMessage());
        if ($e->getCode() === '23000') {
            return "That phone number or email is already used by another {$role}.";
        }
        return "Could not set up the {$role} profile.";
    }
}

/**
 * Approve a role request, grant the role, and provision its record.
 * @param int $requestId - Role request ID
 * @return bool|string - Success status or error message
 */
function approveRoleRequest($requestId) {
    global $dbh;
    
    try {
        // Get the request details
        $stmt = $dbh->prepare("SELECT user_id, requested_role FROM role_requests WHERE id = ? AND status = 'pending'");
        $stmt->execute([$requestId]);
        $request = $stmt->fetch(PDO::FETCH_ASSOC);
        
        if (!$request) {
            return "Role request not found or already processed.";
        }
        
        $userId = $request['user_id'];
        $requestedRole = $request['requested_role'];
        
        // Check if user already has any extra role
        $extraRoles = getActiveExtraRoles($userId);
        if (count($extraRoles) >= 1) {
            $existingRole = ucfirst($extraRoles[0]);
            return "User already has a {$existingRole} role. Cannot add another extra role.";
        }
        
        // Add the role
        $addResult = addRoleToUser($userId, $requestedRole, 'active');
        if ($addResult !== true) {
            return $addResult;
        }

        // Granting the role is not enough on its own: the rider and vendor
        // apps resolve the signed-in account through riders.user_id /
        // vendors.user_id. Without a record there, an approved user opens
        // their app and is told "this account is not set up as a rider yet".
        $provision = provisionRoleRecord($userId, $requestedRole);
        if ($provision !== true) {
            return $provision;
        }

        // Update the request status
        $updateStmt = $dbh->prepare("UPDATE role_requests SET status = 'approved' WHERE id = ?");
        $updateStmt->execute([$requestId]);

        try {
            addNotification(
                (int)$userId, 'Application approved',
                "Your {$requestedRole} application has been approved. You can now switch to your new role.",
                'account', null, ['push', 'email']
            );
        } catch (Throwable $e) {
            error_log("Role approval notification failed: " . $e->getMessage());
        }

        return true;
    } catch (PDOException $e) {
        error_log("Approve role request error: " . $e->getMessage());
        return "Database error occurred.";
    }
}

/**
 * Reject a role request
 * @param int $requestId - Role request ID
 * @return bool - Success status
 */
function rejectRoleRequest($requestId) {
    global $dbh;
    try {
        // Fetched first (rather than a bare UPDATE) so the applicant can be
        // told — the AND status='pending' guard mirrors approveRoleRequest()
        // and stops a double-reject sending a second notification.
        $stmt = $dbh->prepare("SELECT user_id, requested_role FROM role_requests WHERE id = ? AND status = 'pending'");
        $stmt->execute([$requestId]);
        $request = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$request) return false;

        $updateStmt = $dbh->prepare("UPDATE role_requests SET status = 'rejected' WHERE id = ?");
        $updateStmt->execute([$requestId]);

        try {
            addNotification(
                (int)$request['user_id'], 'Application update',
                "Your {$request['requested_role']} application was not approved this time.",
                'account', null, ['push', 'email']
            );
        } catch (Throwable $e) {
            error_log("Role rejection notification failed: " . $e->getMessage());
        }

        return true;
    } catch (PDOException $e) {
        error_log("Reject role request error: " . $e->getMessage());
        return false;
    }
}
?>