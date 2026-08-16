<?php
// =============================================================
// includes/categories.php — the one list of product categories.
// =============================================================
// add-product.php and edit-product.php both used a free-text
// <input name="category">, so a product could be filed under any string an
// admin typed. The customer app filters its home-screen bubbles on exact
// category names (HomeScreen.kt's GROCERIES_CATEGORY and
// FRESH_FOOD_CATEGORIES), so a typo like "Fruit" or "Vegatables" did not
// error anywhere — the product simply stopped appearing in the app while
// looking perfectly fine in the admin list.
//
// NOTE the column really is `categry`, not `category`. That misspelling is
// in the original schema and is left alone here: renaming it would touch
// every query that already reads it for no functional gain.
// =============================================================

/**
 * Every category name, alphabetically. Empty array if the table is missing or
 * unreadable — callers must treat that as "fall back to free text" rather than
 * blocking the form, so a broken category table cannot stop the shop adding
 * products.
 */
function allCategoryNames(PDO $dbh): array {
    try {
        $stmt = $dbh->query("SELECT `categry` FROM `category` ORDER BY `categry` ASC");
        $names = $stmt->fetchAll(PDO::FETCH_COLUMN);
        return array_values(array_filter(array_map('trim', $names), fn($n) => $n !== ''));
    } catch (PDOException $e) {
        error_log('categories.php: could not read category table: ' . $e->getMessage());
        return [];
    }
}

/**
 * Whether [$name] is one of the known categories, compared case-insensitively
 * so "groceries" and "Groceries" are the same category rather than two.
 *
 * Returns true when the list is empty: with no list to check against there is
 * nothing to enforce, and refusing every category would take product creation
 * down entirely.
 */
function isKnownCategory(PDO $dbh, string $name): bool {
    $known = allCategoryNames($dbh);
    if (empty($known)) return true;
    foreach ($known as $k) {
        if (strcasecmp(trim($k), trim($name)) === 0) return true;
    }
    return false;
}

/**
 * Resolves [$name] to the canonical spelling from the table, so a value that
 * differs only by case or surrounding space is stored exactly as the category
 * list has it. Returns the input trimmed if there is no match.
 */
function canonicalCategory(PDO $dbh, string $name): string {
    foreach (allCategoryNames($dbh) as $k) {
        if (strcasecmp(trim($k), trim($name)) === 0) return trim($k);
    }
    return trim($name);
}

/**
 * Renders the category picker.
 *
 * [$current] is the value already on the product, if editing. When it is not
 * in the table it is still offered, flagged as unlisted — otherwise opening an
 * older product and saving an unrelated field would silently move it to
 * whichever category happened to sort first.
 *
 * Falls back to a free-text input when the table has no rows, so this can
 * never be the reason a product cannot be created.
 */
function renderCategorySelect(PDO $dbh, string $current = '', string $cssClass = 'w-full px-4 py-2 border rounded'): void {
    $names = allCategoryNames($dbh);
    $current = trim($current);

    if (empty($names)) {
        echo '<input type="text" name="category" value="' . htmlspecialchars($current)
           . '" class="' . htmlspecialchars($cssClass) . '" required>';
        echo '<p class="text-xs text-red-600 mt-1">Category list unavailable — type the name exactly.</p>';
        return;
    }

    $matched = false;
    foreach ($names as $n) {
        if (strcasecmp($n, $current) === 0) { $matched = true; break; }
    }

    echo '<select name="category" class="' . htmlspecialchars($cssClass) . '" required>';
    if ($current === '') {
        echo '<option value="" disabled selected>Choose a category…</option>';
    }
    if ($current !== '' && !$matched) {
        echo '<option value="' . htmlspecialchars($current) . '" selected>'
           . htmlspecialchars($current) . ' (unlisted — not in the category table)</option>';
    }
    foreach ($names as $n) {
        $sel = (strcasecmp($n, $current) === 0) ? ' selected' : '';
        echo '<option value="' . htmlspecialchars($n) . '"' . $sel . '>' . htmlspecialchars($n) . '</option>';
    }
    echo '</select>';
}
