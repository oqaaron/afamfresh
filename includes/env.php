<?php
// =============================================================
// AfamFresh - Environment / secrets loader
// =============================================================
// Credentials live in env.yaml, never in source. This reads that
// file and exposes env()/env_required() to the rest of the app.
//
// Lookup order for each key (first hit wins):
//
//   1. A real environment variable ($_ENV / $_SERVER / getenv).
//      Cloud Run injects the values from --env-vars-file this way,
//      so a deployed instance never touches the file at all.
//   2. env.yaml.
//   3. The default passed to env().
//
// File location, first that exists:
//
//   1. $AFAMFRESH_ENV_FILE, if set — for keeping the file outside
//      the document root entirely, which is the safest option.
//   2. <project root>/env.yaml — the same path `gcloud run deploy
//      --env-vars-file env.yaml` expects, so one file serves both.
//
// ⚠️ Option 2 puts the file inside the web root, where Apache will
// happily serve it as plain text (verified: a .yaml under htdocs
// returns 200 with its contents). The .htaccess at the project root
// denies it. If you move this app to a server where .htaccess is
// ignored (AllowOverride None, nginx), that protection silently
// disappears — use AFAMFRESH_ENV_FILE there instead.
// =============================================================

/**
 * Parse a flat YAML mapping (KEY: value per line).
 *
 * Deliberately not a full YAML parser — env.yaml is the format
 * gcloud accepts for --env-vars-file, which is a single flat map of
 * scalars. Nested structures are not supported and never appear here.
 */
function afamfresh_parse_env_file($path) {
    $vars = [];
    $lines = @file($path, FILE_IGNORE_NEW_LINES);
    if ($lines === false) {
        return $vars;
    }

    foreach ($lines as $line) {
        $trimmed = trim($line);
        if ($trimmed === '' || $trimmed[0] === '#') {
            continue;
        }

        $split = strpos($trimmed, ':');
        if ($split === false) {
            continue;
        }

        $key = trim(substr($trimmed, 0, $split));
        $value = trim(substr($trimmed, $split + 1));

        if ($key === '') {
            continue;
        }

        $quote = $value === '' ? '' : $value[0];
        if (($quote === '"' || $quote === "'") && substr($value, -1) === $quote && strlen($value) > 1) {
            // Quoted: take it verbatim, minus the quotes. A value that
            // contains " #" must be quoted or the strip below eats it.
            $value = substr($value, 1, -1);
        } else {
            // Unquoted: YAML treats " #" as the start of a comment, and
            // so does gcloud. Mirror that rather than diverging from the
            // tool that reads the same file at deploy time.
            $hash = strpos($value, ' #');
            if ($hash !== false) {
                $value = rtrim(substr($value, 0, $hash));
            }
        }

        $vars[$key] = $value;
    }

    return $vars;
}

/**
 * Locate and cache the parsed env file.
 */
function afamfresh_env_vars() {
    static $vars = null;
    if ($vars !== null) {
        return $vars;
    }

    $candidates = [];

    $override = getenv('AFAMFRESH_ENV_FILE');
    if ($override !== false && $override !== '') {
        $candidates[] = $override;
    }
    $candidates[] = dirname(__DIR__) . '/env.yaml';

    foreach ($candidates as $candidate) {
        if (is_readable($candidate)) {
            $vars = afamfresh_parse_env_file($candidate);
            return $vars;
        }

        // Present but unreadable is the trap worth naming: PHP runs as the
        // web server's user (daemon under XAMPP, www-data elsewhere), not as
        // the person who created the file. A chmod 600 owned by your login
        // account locks the server out, every credential resolves to empty,
        // and the only symptom is third-party calls failing for no visible
        // reason. Needs to be group-readable by the server's group.
        if (file_exists($candidate)) {
            error_log(
                "[config] $candidate exists but is not readable by " .
                (function_exists('posix_getpwuid') && function_exists('posix_geteuid')
                    ? (posix_getpwuid(posix_geteuid())['name'] ?? 'the web server user')
                    : 'the web server user') .
                " — check its ownership and permissions."
            );
        }
    }

    // No file is not fatal on its own: Cloud Run supplies real
    // environment variables, and env_required() below reports any
    // key that ends up missing from every source.
    $vars = [];
    return $vars;
}

/**
 * Read a configuration value.
 */
function env($key, $default = null) {
    // getenv() alone misses variables set via Apache SetEnv in some
    // SAPIs, so check the superglobals too.
    if (array_key_exists($key, $_ENV) && $_ENV[$key] !== '') {
        return $_ENV[$key];
    }
    if (array_key_exists($key, $_SERVER) && $_SERVER[$key] !== '') {
        return $_SERVER[$key];
    }

    $fromEnv = getenv($key);
    if ($fromEnv !== false && $fromEnv !== '') {
        return $fromEnv;
    }

    $vars = afamfresh_env_vars();
    if (array_key_exists($key, $vars) && $vars[$key] !== '') {
        return $vars[$key];
    }

    return $default;
}

/**
 * Read a secret that the app cannot work correctly without.
 *
 * Returns '' and logs when missing rather than aborting: one absent
 * SMS key should not take down checkout, and the calling code already
 * handles empty credentials by failing that one integration. The log
 * line is what turns "the integration silently does nothing" into
 * something diagnosable.
 */
function env_required($key, $usedFor = '') {
    $value = env($key);
    if ($value === null || $value === '') {
        $suffix = $usedFor === '' ? '' : " (needed for $usedFor)";
        error_log("[config] Missing credential $key$suffix — set it in env.yaml or the environment.");
        return '';
    }
    return $value;
}
