<?php
if (!isset($_SESSION['user_id'])) {
    return;
}
require_once __DIR__ . '/roles.php';

$userId = $_SESSION['user_id'];
$activeRoles = getActiveRoles($userId);
$currentRole = $_SESSION['current_role'] ?? 'user';

if (count($activeRoles) <= 1) {
    return;
}

$roleLabels = [
    'user' => '🛒 Shop',
    'vendor' => '🏪 Vendor',
    'rider' => '🚴 Rider',
    'wholesaler' => '📦 Wholesaler'
];
?>
<div class="role-switcher-container">
    <div class="role-switcher-dropdown">
        <button class="role-switcher-btn" id="roleSwitcherBtn" aria-label="Switch role">
            <span class="current-role-icon"><?php echo $roleLabels[$currentRole] ?? '👤'; ?></span>
            <span class="current-role-label"><?php echo ucfirst($currentRole); ?></span>
            <i class="fa fa-chevron-down" aria-hidden="true"></i>
        </button>
        <div class="role-switcher-menu" id="roleSwitcherMenu" role="menu">
            <?php foreach ($activeRoles as $role): ?>
                <?php if ($role !== $currentRole): ?>
                    <a href="/switch-role.php?role=<?php echo $role; ?>" class="role-option" role="menuitem">
                        <span class="role-icon"><?php echo $roleLabels[$role] ?? '👤'; ?></span>
                        <span class="role-label"><?php echo ucfirst($role); ?></span>
                    </a>
                <?php endif; ?>
            <?php endforeach; ?>
        </div>
    </div>
</div>

<style>
/* (same styles as before, but ensure no label warnings) */
</style>

<script>
document.addEventListener('DOMContentLoaded', function() {
    const btn = document.getElementById('roleSwitcherBtn');
    const menu = document.getElementById('roleSwitcherMenu');
    if (btn && menu) {
        btn.addEventListener('click', function(e) {
            e.stopPropagation();
            menu.classList.toggle('show');
        });
        document.addEventListener('click', function(e) {
            if (!btn.contains(e.target) && !menu.contains(e.target)) {
                menu.classList.remove('show');
            }
        });
    }
});
</script>