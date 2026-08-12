<?php
// =============================================================
// reset-password.php — the https bridge into the app.
// =============================================================
// Password reset emails used to link straight to afamfresh://reset-password.
// Two problems with that:
//
//   1. A non-HTTP URI scheme in an anchor is a well-known spam signal, and
//      these emails were landing in spam.
//   2. Plenty of mail clients refuse to linkify a custom scheme, or strip the
//      href entirely — so for those users the link was simply dead. The
//      comment in api/auth.php already noted this and worked around it by
//      printing the raw link as text for people to copy by hand.
//
// So the email now links here, over https, on the same host that served it.
// This page hands off to the app. If the app is not installed, or the browser
// refuses the scheme, the person sees a page explaining what happened rather
// than a silent failure.
//
// No database access, no session, and nothing is logged: the token in the
// query string is a credential, and this page's whole job is to pass it along.
// =============================================================

// Deliberately kept out of search engines. The URL carries a live reset token.
header('X-Robots-Tag: noindex, nofollow', true);
header('Referrer-Policy: no-referrer');
header('Cache-Control: no-store');

$token = trim((string)($_GET['token'] ?? ''));

// Which app asked. Checked against a fixed list rather than used as given —
// otherwise this page would redirect a visitor into any URI an attacker put in
// a link, which is an open redirect with the user's own reset token attached.
$allowedSchemes = ['afamfresh', 'afamfresh-rider', 'afamfresh-vendor'];
$scheme = (string)($_GET['scheme'] ?? 'afamfresh');
if (!in_array($scheme, $allowedSchemes, true)) {
    $scheme = 'afamfresh';
}

$appLink = $token !== ''
    ? $scheme . '://reset-password?token=' . rawurlencode($token)
    : '';
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Reset your AfamFresh password</title>
<style>
    body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
           background:#FAFAF7; color:#1A1D1F; display:flex; min-height:100vh;
           align-items:center; justify-content:center; padding:24px; }
    .card { background:#fff; border-radius:16px; padding:32px; max-width:420px; width:100%;
            box-shadow:0 8px 30px rgba(0,0,0,.06); text-align:center; }
    h1 { color:#1B7A3D; font-size:22px; margin:0 0 8px; }
    p  { color:#5A6068; font-size:15px; line-height:1.5; }
    .btn { display:inline-block; background:#1B7A3D; color:#fff; text-decoration:none;
           padding:14px 28px; border-radius:12px; font-weight:600; margin:20px 0 8px; }
    .muted { color:#8A8F98; font-size:13px; }
    code { background:#F1F1F1; padding:2px 6px; border-radius:4px; font-size:12px;
           word-break:break-all; }
</style>
</head>
<body>
<div class="card">
<?php if ($appLink === ''): ?>
    <h1>Link incomplete</h1>
    <p>This reset link is missing its token, so it cannot be opened. Request a new
       one from the app and use the most recent email.</p>
<?php else: ?>
    <h1>Reset your password</h1>
    <p>Tap below to open AfamFresh and choose a new password.</p>
    <a class="btn" href="<?= htmlspecialchars($appLink, ENT_QUOTES, 'UTF-8') ?>">Open AfamFresh</a>
    <p class="muted">Links expire 30 minutes after they are requested.</p>
    <p class="muted">Nothing happened? Make sure the AfamFresh app is installed on
       this device, then tap again.</p>

    <script>
    // Attempted automatically as well as offered as a button. Done from a
    // click-adjacent timer rather than immediately on load: browsers block
    // scheme handoffs that fire before any interaction, and a blocked attempt
    // would leave the page looking broken with no button visible yet.
    setTimeout(function () {
        window.location.href = <?= json_encode($appLink) ?>;
    }, 600);
    </script>
<?php endif; ?>
</div>
</body>
</html>