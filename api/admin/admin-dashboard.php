<?php
// admin/admin-dashboard.php
//
// The path was 'includes/auth_check.php', but auth_check.php sits in admin/,
// not admin/includes/ -- so this page was a fatal error every time it was
// opened, for as long as it has existed. Nothing linked to it until the nav
// gained a Vendor Verification entry, which is why it went unnoticed.
//
// __DIR__ rather than another relative path: relative includes resolve against
// the calling file's directory OR the working directory depending on how PHP
// was reached, and this file is opened directly by Apache.
require_once __DIR__ . '/auth_check.php';

// $dbh, for the sidebar's pending-requests badge.
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
// This page's three cards (Bulk listings, cancellation requests/refunds,
// vendor verification) belong to three different permissions -- reachable
// with any one of them; each underlying AJAX action in
// api/admin/Bulk-approval.php / vendors.php enforces its own specific one.
requireAnyAdminPermission(['Bulk.manage_listings', 'Bulk.manage_refunds', 'vendors.manage']);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh Admin Dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <!-- The shared sidebar draws its icons with Font Awesome; this page did not
         load it, so every nav item rendered as a blank square. -->
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
    <style>
        .card { transition: all 0.2s; }
        .card:hover { box-shadow: 0 8px 20px rgba(0,0,0,0.08); }
        .btn { transition: all 0.15s; cursor: pointer; }
        .btn:active { transform: scale(0.96); }
        .spinner { border: 3px solid #f3f3f3; border-top: 3px solid #0D2E18; border-radius: 50%; width: 24px; height: 24px; animation: spin 0.8s linear infinite; display: inline-block; }
        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
    </style>
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">

    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <!-- Header -->
        <div class="flex justify-between items-center mb-8">
            <div>
                <h1 class="text-3xl font-bold text-green-800">🛠️ AfamFresh Admin</h1>
                <p class="text-gray-600 mt-1">Manage your platform settings, Bulk approvals, and vendor accounts.</p>
            </div>
            <div class="flex items-center space-x-4">
                <span class="text-sm text-gray-600">Welcome, <strong><?= htmlspecialchars($_SESSION['admin_name'] ?? 'Admin') ?></strong></span>
                <a href="logout.php" class="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded-lg text-sm font-semibold transition">Logout</a>
            </div>
        </div>

        <!-- Status Messages -->
        <div id="message" class="mb-6 hidden rounded-lg px-4 py-3 font-medium"></div>

        <!-- ====== CONFIGURATION SECTION ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 mb-8 card">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">⚙️ Remote Configuration</h2>
            <form id="configForm" class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                    <label class="block text-sm font-medium text-gray-700">Minimum Required Version</label>
                    <input type="text" id="min_version_required" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent">
                </div>
                <div>
                    <label class="block text-sm font-medium text-gray-700">Current Live Version</label>
                    <input type="text" id="current_version" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent">
                </div>
                <div class="flex items-center space-x-2">
                    <input type="checkbox" id="is_maintenance_mode" value="1" class="w-5 h-5 text-green-600 border-gray-300 rounded focus:ring-green-500">
                    <label for="is_maintenance_mode" class="text-sm font-medium text-gray-700">Maintenance Mode (active)</label>
                </div>
                <div class="flex items-center space-x-2">
                    <input type="checkbox" id="Bulk_approval_required" value="1" class="w-5 h-5 text-green-600 border-gray-300 rounded focus:ring-green-500">
                    <label for="Bulk_approval_required" class="text-sm font-medium text-gray-700">Bulk Approval Required</label>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-sm font-medium text-gray-700">Maintenance Message</label>
                    <textarea id="maintenance_message" rows="2" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent"></textarea>
                </div>
                <div class="md:col-span-2">
                    <button type="button" onclick="saveConfig()" class="bg-green-700 hover:bg-green-800 text-white px-6 py-2 rounded-lg font-semibold transition btn">💾 Push Changes</button>
                </div>
            </form>
        </div>

        <!-- ====== Bulk APPROVALS ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 mb-8 card">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">📦 Pending Bulk Approvals</h2>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Vendor</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Price (UGX)</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Qty</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Expiry</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                        </tr>
                    </thead>
                    <tbody id="pendingTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>

            <!-- Vendor-requested cancellations: a vendor asked to cancel a
                 PAID order; nothing has actually happened yet until approved
                 or denied here. See includes/Bulk_payment.php's
                 requestBulkOrderCancellation()/cancelBulkOrder(). -->
            <h3 class="text-md font-semibold text-gray-700 mt-8 mb-3 border-t pt-6">🚫 Cancellation Requests Awaiting Approval</h3>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Order</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Vendor</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Reason</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Requested</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                        </tr>
                    </thead>
                    <tbody id="cancellationsTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="6" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>

            <!-- Refunds Pesapal has been asked for but not yet confirmed
                 complete -- confirming here is what finally moves status to
                 'refunded', once the admin has checked Pesapal's dashboard. -->
            <h3 class="text-md font-semibold text-gray-700 mt-8 mb-3 border-t pt-6">💸 Refunds Awaiting Confirmation</h3>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Order</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Vendor</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Amount (UGX)</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Requested</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                        </tr>
                    </thead>
                    <tbody id="refundsTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="6" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- ====== VENDOR REGISTRY ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 card">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">🏪 Vendor Registry</h2>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Business</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Email</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Type</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Listings</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Action</th>
                        </tr>
                    </thead>
                    <tbody id="vendorsTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <script>
        // Sent as X-CSRF-Token on every mutating request below — verifyCsrfHeader()
        // on the PHP side checks it against this same session's token.
        const CSRF_TOKEN = <?= json_encode(csrfToken()) ?>;

        // Helper: show notification
        function showNotification(text, type = 'success') {
            const msg = document.getElementById('message');
            msg.className = 'mb-6 rounded-lg px-4 py-3 font-medium ' + (type === 'success' ? 'bg-green-100 text-green-800 border border-green-300' : 'bg-red-100 text-red-800 border border-red-300');
            msg.textContent = text;
            msg.classList.remove('hidden');
            setTimeout(() => msg.classList.add('hidden'), 5000);
        }

        // Generic API request with error handling
        async function apiRequest(url, options = {}) {
            try {
                const response = await fetch(url, {
                    ...options,
                    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': CSRF_TOKEN, ...(options.headers || {}) }
                });
                if (!response.ok) {
                    const errorText = await response.text();
                    throw new Error(`HTTP ${response.status}: ${errorText}`);
                }
                return await response.json();
            } catch (e) {
                console.error('API Request failed:', e);
                return { success: false, error: e.message || 'Network error' };
            }
        }

        // ---- CONFIG ----
        async function loadConfig() {
            const res = await apiRequest('../api/config.php');
            if (res.success && res.config) {
                const data = res.config;
                document.getElementById('min_version_required').value = data.min_version_required || '';
                document.getElementById('current_version').value = data.current_version || '';
                document.getElementById('is_maintenance_mode').checked = data.is_maintenance_mode === '1';
                document.getElementById('maintenance_message').value = data.maintenance_message || '';
                document.getElementById('Bulk_approval_required').checked = data.Bulk_approval_required === '1';
            } else {
                showNotification('Failed to load configuration: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        async function saveConfig() {
            const payload = {
                min_version_required: document.getElementById('min_version_required').value.trim(),
                current_version: document.getElementById('current_version').value.trim(),
                is_maintenance_mode: document.getElementById('is_maintenance_mode').checked ? '1' : '0',
                maintenance_message: document.getElementById('maintenance_message').value.trim(),
                Bulk_approval_required: document.getElementById('Bulk_approval_required').checked ? '1' : '0'
            };
            const res = await apiRequest('../api/config.php', {
                method: 'PUT',
                body: JSON.stringify(payload)
            });
            if (res.success) {
                showNotification('Configuration saved successfully.');
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- Bulk APPROVALS ----
        async function loadPendingBulk() {
            const tbody = document.getElementById('pendingTableBody');
            tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=pending');
            if (res.success && res.listings) {
                if (res.listings.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">✅ No pending approvals.</td></tr>';
                    return;
                }
                tbody.innerHTML = res.listings.map(item => `
                    <tr>
                        <td class="px-4 py-3 text-sm">${item.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">${escapeHtml(item.product_name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name)}</td>
                        <td class="px-4 py-3 text-sm">${Number(item.discounted_price).toLocaleString()}</td>
                        <td class="px-4 py-3 text-sm">${item.Bulk_quantity}</td>
                        <td class="px-4 py-3 text-sm">${item.expiry_date}</td>
                        <td class="px-4 py-3 text-sm space-x-2">
                            <button onclick="processBulk(${item.id}, 'approve')" class="bg-green-600 hover:bg-green-700 text-white px-3 py-1 rounded text-xs font-semibold btn">Approve</button>
                            <button onclick="processBulk(${item.id}, 'reject')" class="bg-red-600 hover:bg-red-700 text-white px-3 py-1 rounded text-xs font-semibold btn">Reject</button>
                        </td>
                    </tr>
                `).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="7" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function processBulk(id, action) {
            if (!confirm(`Are you sure you want to ${action} this listing?`)) return;
            const res = await apiRequest(`../api/admin/Bulk-approval.php?action=${action}`, {
                method: 'POST',
                body: JSON.stringify({ id })
            });
            if (res.success) {
                showNotification(`Listing ${action}d successfully.`);
                loadPendingBulk();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- Bulk ORDER CANCELLATION REQUESTS ----
        async function loadPendingCancellations() {
            const tbody = document.getElementById('cancellationsTableBody');
            tbody.innerHTML = '<tr><td colspan="6" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=pending_cancellations');
            if (res.success && res.cancellations) {
                if (res.cancellations.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" class="px-4 py-4 text-center text-gray-500">✅ Nothing awaiting approval.</td></tr>';
                    return;
                }
                tbody.innerHTML = res.cancellations.map(item => `
                    <tr>
                        <td class="px-4 py-3 text-sm">#${item.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">${escapeHtml(item.product_name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.cancellation_reason || '—')}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.cancellation_requested_at)}</td>
                        <td class="px-4 py-3 text-sm space-x-2">
                            <button onclick="approveCancellation(${item.id})" class="bg-red-600 hover:bg-red-700 text-white px-3 py-1 rounded text-xs font-semibold btn">Approve</button>
                            <button onclick="denyCancellation(${item.id})" class="bg-gray-200 hover:bg-gray-300 text-gray-800 px-3 py-1 rounded text-xs font-semibold btn">Deny</button>
                        </td>
                    </tr>
                `).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="6" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function approveCancellation(id) {
            if (!confirm('Approve this cancellation? Stock returns to the listing and a refund will be requested if it was paid.')) return;
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=approve_cancellation', {
                method: 'POST',
                body: JSON.stringify({ id })
            });
            if (res.success) {
                showNotification(res.refund_requested
                    ? 'Approved. Cancelled and a refund was requested from Pesapal.'
                    : 'Approved and cancelled.');
                loadPendingCancellations();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        async function denyCancellation(id) {
            const reason = prompt('Reason for denying this cancellation (the vendor sees it):');
            if (reason === null) return;
            if (!reason.trim()) {
                showNotification('A reason is required to deny.', 'error');
                return;
            }
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=deny_cancellation', {
                method: 'POST',
                body: JSON.stringify({ id, reason: reason.trim() })
            });
            if (res.success) {
                showNotification('Denied. The order continues as normal.');
                loadPendingCancellations();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- Bulk REFUNDS AWAITING CONFIRMATION ----
        async function loadPendingRefunds() {
            const tbody = document.getElementById('refundsTableBody');
            tbody.innerHTML = '<tr><td colspan="6" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=pending_refunds');
            if (res.success && res.refunds) {
                if (res.refunds.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" class="px-4 py-4 text-center text-gray-500">✅ Nothing awaiting confirmation.</td></tr>';
                    return;
                }
                tbody.innerHTML = res.refunds.map(item => `
                    <tr>
                        <td class="px-4 py-3 text-sm">#${item.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">${escapeHtml(item.product_name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name)}</td>
                        <td class="px-4 py-3 text-sm">${Number(Number(item.total_price) + Number(item.delivery_fee)).toLocaleString()}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.updated_at)}</td>
                        <td class="px-4 py-3 text-sm">
                            <button onclick="confirmRefund(${item.id})" class="bg-amber-600 hover:bg-amber-700 text-white px-3 py-1 rounded text-xs font-semibold btn">Confirm refund complete</button>
                        </td>
                    </tr>
                `).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="6" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function confirmRefund(id) {
            if (!confirm('Confirm the refund has actually completed in Pesapal?')) return;
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=confirm_refund', {
                method: 'POST',
                body: JSON.stringify({ id })
            });
            if (res.success) {
                showNotification('Refund confirmed and the customer told.');
                loadPendingRefunds();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- VENDORS ----
        async function loadVendors() {
            const tbody = document.getElementById('vendorsTableBody');
            tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/vendors.php?action=list');
            if (res.success && res.vendors) {
                if (res.vendors.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">No vendors registered.</td></tr>';
                    return;
                }
                tbody.innerHTML = res.vendors.map(v => `
                    <tr>
                        <td class="px-4 py-3 text-sm">${v.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">${escapeHtml(v.business_name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(v.email)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(v.business_type)}</td>
                        <td class="px-4 py-3 text-sm">${v.total_listings}</td>
                        <td class="px-4 py-3 text-sm">
                            <span class="px-2 py-1 rounded-full text-xs font-semibold ${v.is_verified ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'}">
                                ${v.is_verified ? '✅ Verified' : '⏳ Pending'}
                            </span>
                        </td>
                        <td class="px-4 py-3 text-sm">
                            <button onclick="toggleVendor(${v.id}, ${v.is_verified ? 0 : 1})" class="bg-blue-600 hover:bg-blue-700 text-white px-3 py-1 rounded text-xs font-semibold btn">
                                ${v.is_verified ? 'Revoke' : 'Verify'}
                            </button>
                        </td>
                    </tr>
                `).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="7" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function toggleVendor(id, nextState) {
            if (!confirm(`Set verification to ${nextState ? 'VERIFIED' : 'UNVERIFIED'}?`)) return;
            const res = await apiRequest('../api/admin/vendors.php?action=toggle_verification', {
                method: 'POST',
                body: JSON.stringify({ id, verified: nextState })
            });
            if (res.success) {
                showNotification(res.message || 'Vendor status updated.');
                loadVendors();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // Helper to escape HTML to prevent XSS
        function escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // ---- INIT ----
        document.addEventListener('DOMContentLoaded', function() {
            loadConfig();
            loadPendingBulk();
            loadPendingCancellations();
            loadPendingRefunds();
            loadVendors();
        });
    </script>
</div><!-- /content -->
</div><!-- /flex wrapper opened before the sidebar include -->
</body>
</html>