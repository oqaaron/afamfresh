<?php
// =============================================================
// includes/csrf.php — CSRF token helper for admin's POST forms.
// =============================================================
// admin/*.php has 17 pages that mutate state through plain POST forms with
// no token anywhere, so any page a logged-in admin's browser is tricked
// into loading (an image tag, an auto-submitting form on another site) can
// submit one of those forms using the admin's own session cookie. Requires
// session_start() to have already run — every caller already gets that
// from admin/includes/config.php.
// =============================================================

/**
 * Returns the current session's CSRF token, generating one if this is the
 * first call this session.
 */
function csrfToken(): string {
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

/** A ready-to-echo hidden input for a POST <form>. */
function csrfField(): string {
    return '<input type="hidden" name="csrf_token" value="' . htmlspecialchars(csrfToken()) . '">';
}

/**
 * Call as the first statement inside every POST handler. Stops the request
 * with 403 on a missing or mismatched token — hash_equals() rather than ===
 * so the comparison itself can't leak the real token through timing.
 */
function verifyCsrf(): void {
    $submitted = $_POST['csrf_token'] ?? '';
    if (!hash_equals($_SESSION['csrf_token'] ?? '', $submitted)) {
        http_response_code(403);
        echo 'Your session expired or this form was submitted from somewhere unexpected. Please go back, refresh the page, and try again.';
        exit;
    }
}

/**
 * Same check as verifyCsrf(), for a JSON fetch() POST instead of a <form>.
 * $_POST is empty for an application/json body, so the token travels as a
 * header instead — the caller embeds csrfToken() in the page once and sends
 * it back as X-CSRF-Token on every mutating request.
 */
function verifyCsrfHeader(): void {
    $submitted = $_SERVER['HTTP_X_CSRF_TOKEN'] ?? '';
    if (!hash_equals($_SESSION['csrf_token'] ?? '', $submitted)) {
        http_response_code(403);
        echo json_encode(['success' => false, 'error' => 'Your session expired. Please refresh the page and try again.']);
        exit;
    }
}
