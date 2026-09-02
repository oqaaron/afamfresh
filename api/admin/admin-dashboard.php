<?php
// admin/admin-dashboard.php
// Refactored to support the unified Merchants architecture & dynamic commission configuration.

require_once __DIR__ . '/auth_check.php';
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';

requireAnyAdminPermission(['merchants.manage_listings', 'merchants.manage_refunds', 'vendors.manage', 'Bulk.manage_listings', 'Bulk.manage_refunds']);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh Admin Dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
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
                <p class="text-gray-600 mt-1">Manage platform settings, commission structures, and merchant accounts.</p>
            </div>
            <div class="flex items-center space-x-4">
                <span class="text-sm text-gray-600">Welcome, <strong><?= htmlspecialchars($_SESSION['admin_name'] ?? 'Admin') ?></strong></span>
                <a href="logout.php" class="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded-lg text-sm font-semibold transition">Logout</a>
            </div>
        </div>

        <!-- Status Messages -->
        <div id="message" class="mb-6 hidden rounded-lg px-4 py-3 font-medium"></div>

        <!-- ====== CONFIGURATION & COMMISSIONS SECTION ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 mb-8 card">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">⚙️ Remote Configuration & Platform Commissions</h2>
            <form id="configForm" class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                    <label class="block text-sm font-medium text-gray-700">Minimum Required App Version</label>
                    <input type="text" id="min_version_required" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent text-sm">
                </div>
                <div>
                    <label class="block text-sm font-medium text-gray-700">Current Live App Version</label>
                    <input type="text" id="current_version" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent text-sm">
                </div>
                <div class="flex items-center space-x-2">
                    <input type="checkbox" id="is_maintenance_mode" value="1" class="w-5 h-5 text-green-600 border-gray-300 rounded focus:ring-green-500">
                    <label for="is_maintenance_mode" class="text-sm font-medium text-gray-700">Maintenance Mode (active)</label>
                </div>
                <div class="flex items-center space-x-2">
                    <input type="checkbox" id="merchant_approval_required" value="1" class="w-5 h-5 text-green-600 border-gray-300 rounded focus:ring-green-500">
                    <label for="merchant_approval_required" class="text-sm font-medium text-gray-700">Merchant Item Approval Required</label>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-sm font-medium text-gray-700">Maintenance Message</label>
                    <textarea id="maintenance_message" rows="2" class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-green-600 focus:border-transparent text-sm"></textarea>
                </div>

                <!-- Global Commission Defaults -->
                <div class="md:col-span-2 border-t pt-4 mt-2">
                    <h3 class="text-sm font-bold text-gray-700 uppercase tracking-wider mb-3">Default Platform Commissions (%)</h3>
                    <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Riders (%)</label>
                            <input type="number" step="0.1" min="0" max="100" id="default_rider_commission_rate" class="mt-1 w-full border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-green-800">
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Standard Vendors (%)</label>
                            <input type="number" step="0.1" min="0" max="100" id="default_vendor_commission_rate" class="mt-1 w-full border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-green-800">
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Market Produce (%)</label>
                            <input type="number" step="0.1" min="0" max="100" id="default_market_vendor_commission_rate" class="mt-1 w-full border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-green-800">
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Restaurants (%)</label>
                            <input type="number" step="0.1" min="0" max="100" id="default_fastfood_commission_rate" class="mt-1 w-full border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-green-800">
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Wholesale (%)</label>
                            <input type="number" step="0.1" min="0" max="100" id="default_wholesale_commission_rate" class="mt-1 w-full border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-green-800">
                        </div>
                    </div>
                </div>

                <div class="md:col-span-2 mt-2">
                    <button type="button" onclick="saveConfig()" class="bg-green-700 hover:bg-green-800 text-white px-6 py-2 rounded-lg font-semibold transition btn">💾 Push Configuration</button>
                </div>
            </form>
        </div>

        <!-- ====== MERCHANT CATALOG APPROVALS ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 mb-8 card">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">📦 Pending Merchant Listings</h2>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Merchant</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Type</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Price (UGX)</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Stock Qty</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                        </tr>
                    </thead>
                    <tbody id="pendingTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>

            <!-- Merchant Order Cancellation Requests -->
            <h3 class="text-md font-semibold text-gray-700 mt-8 mb-3 border-t pt-6">🚫 Cancellation Requests Awaiting Approval</h3>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Order</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Merchant</th>
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

            <!-- Refunds Awaiting Confirmation -->
            <h3 class="text-md font-semibold text-gray-700 mt-8 mb-3 border-t pt-6">💸 Refunds Awaiting Confirmation</h3>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Order</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Merchant</th>
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

        <!-- ====== MERCHANT REGISTRY ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 card mb-8">
            <h2 class="text-xl font-semibold text-green-800 border-b border-gray-200 pb-3 mb-6">🏪 Merchant Registry</h2>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Business</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Email</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Category</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Commission Rate</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Min Payout</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Action</th>
                        </tr>
                    </thead>
                    <tbody id="vendorsTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="8" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- ====== MERCHANT TRANSACTIONS LEDGER ====== -->
        <div class="bg-white rounded-xl shadow-md p-6 card">
            <div class="flex items-center justify-between border-b border-gray-200 pb-3 mb-6">
                <h2 class="text-xl font-semibold text-green-800">💰 Merchant Transactions Ledger</h2>
                <div class="flex items-center gap-2">
                    <select id="earningsVendorFilter" onchange="loadVendorEarnings()"
                            class="border border-gray-300 rounded px-2 py-1 text-xs">
                        <option value="0">All merchants</option>
                    </select>
                    <a href="vendor-payouts.php"
                       class="text-xs font-semibold text-green-700 hover:underline">Payout requests →</a>
                </div>
            </div>

            <div id="earningsTotals" class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-5"></div>

            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Order</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Merchant</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Item</th>
                            <th class="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Goods</th>
                            <th class="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Commission</th>
                            <th class="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Net to Seller</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Paid Out</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">When</th>
                        </tr>
                    </thead>
                    <tbody id="earningsTableBody" class="divide-y divide-gray-200">
                        <tr><td colspan="8" class="px-4 py-4 text-center text-gray-500">Loading...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <script>
        const CSRF_TOKEN = <?= json_encode(csrfToken()) ?>;

        function showNotification(text, type = 'success') {
            const msg = document.getElementById('message');
            msg.className = 'mb-6 rounded-lg px-4 py-3 font-medium ' + (type === 'success' ? 'bg-green-100 text-green-800 border border-green-300' : 'bg-red-100 text-red-800 border border-red-300');
            msg.textContent = text;
            msg.classList.remove('hidden');
            setTimeout(() => msg.classList.add('hidden'), 5000);
        }

        async function apiRequest(url, options = {}) {
            try {
                const response = await fetch(url, {
                    ...options,
                    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': CSRF_TOKEN, ...(options.headers || {}) }
                });

                const rawBody = await response.text();
                let parsed = null;
                try { parsed = JSON.parse(rawBody); } catch (_) { }

                if (!response.ok) {
                    const fallback = response.status === 401
                        ? 'Your admin session has expired. Please sign in again.'
                        : `Request failed (HTTP ${response.status}).`;
                    return { success: false, error: (parsed && parsed.error) || fallback };
                }
                if (parsed === null) {
                    return { success: false, error: 'The server returned an unreadable response.' };
                }
                return parsed;
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
                document.getElementById('merchant_approval_required').checked = (data.merchant_approval_required === '1' || data.Bulk_approval_required === '1');

                document.getElementById('default_rider_commission_rate').value = data.default_rider_commission_rate || '15.0';
                document.getElementById('default_vendor_commission_rate').value = data.default_vendor_commission_rate || '10.0';
                document.getElementById('default_market_vendor_commission_rate').value = data.default_market_vendor_commission_rate || '6.0';
                document.getElementById('default_fastfood_commission_rate').value = data.default_fastfood_commission_rate || '18.0';
                document.getElementById('default_wholesale_commission_rate').value = data.default_wholesale_commission_rate || '3.5';
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
                merchant_approval_required: document.getElementById('merchant_approval_required').checked ? '1' : '0',
                Bulk_approval_required: document.getElementById('merchant_approval_required').checked ? '1' : '0',
                default_rider_commission_rate: document.getElementById('default_rider_commission_rate').value.trim(),
                default_vendor_commission_rate: document.getElementById('default_vendor_commission_rate').value.trim(),
                default_market_vendor_commission_rate: document.getElementById('default_market_vendor_commission_rate').value.trim(),
                default_fastfood_commission_rate: document.getElementById('default_fastfood_commission_rate').value.trim(),
                default_wholesale_commission_rate: document.getElementById('default_wholesale_commission_rate').value.trim()
            };
            const res = await apiRequest('../api/config.php', {
                method: 'PUT',
                body: JSON.stringify(payload)
            });
            if (res.success) {
                showNotification('Configuration and platform commission settings saved.');
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- PENDING MERCHANT LISTINGS ----
        async function loadPendingBulk() {
            const tbody = document.getElementById('pendingTableBody');
            tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=pending');
            if (res.success && (res.listings || res.items)) {
                const list = res.listings || res.items;
                if (list.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="px-4 py-4 text-center text-gray-500">✅ No pending approvals.</td></tr>';
                    return;
                }
                tbody.innerHTML = list.map(item => `
                    <tr>
                        <td class="px-4 py-3 text-sm">${item.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">${escapeHtml(item.product_name || item.name)}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name || item.merchant_name || '—')}</td>
                        <td class="px-4 py-3 text-sm">
                            <span class="px-2 py-0.5 rounded text-xs font-semibold bg-gray-100 text-gray-800">
                                ${escapeHtml((item.merchant_type || 'vendor').replace('_', ' '))}
                            </span>
                        </td>
                        <td class="px-4 py-3 text-sm">${Number(item.discounted_price || item.price).toLocaleString()}</td>
                        <td class="px-4 py-3 text-sm">${item.Bulk_quantity || item.stock_qty}</td>
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

        // ---- CANCELLATIONS & REFUNDS ----
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
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name || '—')}</td>
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
            if (!confirm('Approve this cancellation? Stock returns to listing and a refund is initiated if paid.')) return;
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=approve_cancellation', {
                method: 'POST',
                body: JSON.stringify({ id })
            });
            if (res.success) {
                showNotification(res.refund_requested ? 'Approved. Pesapal refund initiated.' : 'Approved and cancelled.');
                loadPendingCancellations();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        async function denyCancellation(id) {
            const reason = prompt('Reason for denying this cancellation:');
            if (!reason || !reason.trim()) return;
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=deny_cancellation', {
                method: 'POST',
                body: JSON.stringify({ id, reason: reason.trim() })
            });
            if (res.success) {
                showNotification('Denied. Order continues.');
                loadPendingCancellations();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

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
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.business_name || '—')}</td>
                        <td class="px-4 py-3 text-sm">${Number(Number(item.total_price) + Number(item.delivery_fee)).toLocaleString()}</td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(item.updated_at)}</td>
                        <td class="px-4 py-3 text-sm">
                            <button onclick="confirmRefund(${item.id})" class="bg-amber-600 hover:bg-amber-700 text-white px-3 py-1 rounded text-xs font-semibold btn">Confirm Refund Complete</button>
                        </td>
                    </tr>
                `).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="6" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function confirmRefund(id) {
            if (!confirm('Confirm refund status completed on Pesapal?')) return;
            const res = await apiRequest('../api/admin/Bulk-approval.php?action=confirm_refund', {
                method: 'POST',
                body: JSON.stringify({ id })
            });
            if (res.success) {
                showNotification('Refund marked complete.');
                loadPendingRefunds();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- MERCHANTS REGISTRY ----
        const MERCHANT_TYPES = [
            { id: 'vendor', label: 'Standard Vendor' },
            { id: 'market_vendor', label: 'Market Produce' },
            { id: 'fastfood_restaurant', label: 'Fast Food & Restaurant' },
            { id: 'wholesale', label: 'Wholesale Depot' }
        ];

        async function loadVendors() {
            const tbody = document.getElementById('vendorsTableBody');
            tbody.innerHTML = '<tr><td colspan="8" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';
            const res = await apiRequest('../api/admin/vendors.php?action=list');
            if (res.success && (res.vendors || res.merchants)) {
                const list = res.vendors || res.merchants;
                fillEarningsVendorFilter(list);
                if (list.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="8" class="px-4 py-4 text-center text-gray-500">No merchants registered.</td></tr>';
                    return;
                }
                tbody.innerHTML = list.map(v => {
                    const currentType = v.merchant_type || v.business_type || 'vendor';
                    const commPercent = Number(v.commission_rate ? (v.commission_rate * 100) : 10.0).toFixed(1);
                    const minPayout = v.min_payout_threshold || (currentType === 'wholesale' ? 50000 : 5000);

                    return `
                    <tr>
                        <td class="px-4 py-3 text-sm">${v.id}</td>
                        <td class="px-4 py-3 text-sm font-medium">
                            ${escapeHtml(v.business_name || v.name)}
                            <span class="text-xs text-gray-400 block">${escapeHtml(v.area || 'Kampala')}</span>
                        </td>
                        <td class="px-4 py-3 text-sm">${escapeHtml(v.email || '—')}</td>
                        <td class="px-4 py-3 text-sm">
                            <select onchange="setMerchantType(${v.id}, this.value, this)"
                                    class="border border-gray-300 rounded px-2 py-1 text-xs font-medium">
                                ${MERCHANT_TYPES.map(t => `
                                    <option value="${t.id}"${currentType === t.id ? ' selected' : ''}>${t.label}</option>
                                `).join('')}
                            </select>
                        </td>
                        <td class="px-4 py-3 text-sm">
                            <div class="flex items-center space-x-1">
                                <input type="number" step="0.1" min="0" max="100" 
                                       id="comm_rate_${v.id}" 
                                       value="${commPercent}" 
                                       class="w-16 border border-gray-300 rounded px-1.5 py-0.5 text-xs text-right font-semibold text-green-800 focus:ring-1 focus:ring-green-600">
                                <span class="text-xs text-gray-500 font-semibold">%</span>
                                <button onclick="saveCustomCommission(${v.id})" 
                                        title="Save custom rate" 
                                        class="bg-gray-100 hover:bg-green-600 hover:text-white text-gray-700 px-2 py-0.5 rounded text-xs border border-gray-300 transition font-bold">
                                    ✓
                                </button>
                            </div>
                        </td>
                        <td class="px-4 py-3 text-sm text-gray-600">
                            UGX ${Number(minPayout).toLocaleString()}
                        </td>
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
                `;}).join('');
            } else {
                tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-4 text-center text-red-600">Error: ${res.error || 'Failed to load'}</td></tr>`;
            }
        }

        async function setMerchantType(id, type, el) {
            const matched = MERCHANT_TYPES.find(m => m.id === type);
            const label = matched ? matched.label : type;
            if (!confirm(`Update merchant classification to "${label}"?\n\nThis automatically updates commission tiers and minimum payout thresholds.`)) {
                loadVendors();
                return;
            }
            const res = await apiRequest('../api/admin/vendors.php?action=set_business_type', {
                method: 'POST',
                body: JSON.stringify({ id: id, merchant_type: type, business_type: type })
            });
            if (res.success) {
                showNotification(`Merchant tier updated to ${label}.`);
                loadVendors();
            } else {
                showNotification(res.error || 'Could not update merchant tier.', 'error');
                loadVendors();
            }
        }

        async function saveCustomCommission(merchantId) {
            const input = document.getElementById(`comm_rate_${merchantId}`);
            const ratePercent = parseFloat(input.value);

            if (isNaN(ratePercent) || ratePercent < 0 || ratePercent > 100) {
                showNotification('Provide a valid commission percentage between 0 and 100.', 'error');
                return;
            }

            const rateDecimal = ratePercent / 100.0;

            const res = await apiRequest('../api/admin/vendors.php?action=set_commission_rate', {
                method: 'POST',
                body: JSON.stringify({ id: merchantId, commission_rate: rateDecimal })
            });

            if (res.success) {
                showNotification(`Commission for Merchant #${merchantId} saved at ${ratePercent.toFixed(1)}%.`);
                loadVendors();
            } else {
                showNotification(res.error || 'Failed to save commission rate.', 'error');
            }
        }

        async function toggleVendor(id, nextState) {
            if (!confirm(`Set verification status to ${nextState ? 'VERIFIED' : 'UNVERIFIED'}?`)) return;
            const res = await apiRequest('../api/admin/vendors.php?action=toggle_verification', {
                method: 'POST',
                body: JSON.stringify({ id, verified: nextState })
            });
            if (res.success) {
                showNotification(res.message || 'Verification updated.');
                loadVendors();
            } else {
                showNotification('Error: ' + (res.error || 'Unknown error'), 'error');
            }
        }

        // ---- TRANSACTION LEDGER ----
        function ugx(v) {
            return 'UGX ' + Number(v || 0).toLocaleString('en-UG', { maximumFractionDigits: 0 });
        }

        function totalCard(label, value, tone) {
            return `<div class="rounded-lg border ${tone} px-3 py-2">
                        <div class="text-xs text-gray-500">${label}</div>
                        <div class="text-sm font-bold">${value}</div>
                    </div>`;
        }

        async function loadVendorEarnings() {
            const tbody = document.getElementById('earningsTableBody');
            const vendorId = document.getElementById('earningsVendorFilter').value || '0';
            tbody.innerHTML = '<tr><td colspan="8" class="px-4 py-4 text-center text-gray-500"><span class="spinner"></span> Loading...</td></tr>';

            const res = await apiRequest('../api/admin/vendors.php?action=earnings&vendor_id=' + encodeURIComponent(vendorId));
            if (!res.success) {
                tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-4 text-center text-red-600">Error: ${escapeHtml(res.error || 'Failed to load')}</td></tr>`;
                document.getElementById('earningsTotals').innerHTML = '';
                return;
            }

            const t = res.totals || {};
            document.getElementById('earningsTotals').innerHTML =
                totalCard('Delivered Orders', t.transactions || 0, 'border-gray-200') +
                totalCard('Gross Sales', ugx(t.gross), 'border-gray-200') +
                totalCard('Platform Commission', ugx(t.commission), 'border-green-200 bg-green-50') +
                totalCard('Unpaid Balance', ugx(t.unpaid), 'border-yellow-200 bg-yellow-50');

            if (!res.earnings || res.earnings.length === 0) {
                tbody.innerHTML = '<tr><td colspan="8" class="px-4 py-4 text-center text-gray-500">No merchant transactions recorded. Sellers are credited automatically upon delivery confirmation.</td></tr>';
                return;
            }

            tbody.innerHTML = res.earnings.map(e => `
                <tr>
                    <td class="px-4 py-3 text-sm">#${e.order_id}<span class="text-gray-400 text-xs"> ${escapeHtml(e.source || '')}</span></td>
                    <td class="px-4 py-3 text-sm">
                        ${escapeHtml(e.business_name || e.merchant_name || '')}
                        <span class="text-gray-400 text-xs block">${escapeHtml((e.merchant_type || e.business_type || '').replace('_',' '))}</span>
                    </td>
                    <td class="px-4 py-3 text-sm">${escapeHtml(e.product_name || '—')}</td>
                    <td class="px-4 py-3 text-sm text-right">${ugx(e.order_amount)}</td>
                    <td class="px-4 py-3 text-sm text-right text-gray-500">
                        ${ugx(e.commission_amount)}
                        <span class="text-xs font-semibold text-green-700 block">${Number(e.commission_rate || 0).toFixed(1)}%</span>
                    </td>
                    <td class="px-4 py-3 text-sm text-right font-semibold">${ugx(e.net_earnings)}</td>
                    <td class="px-4 py-3 text-sm">
                        <span class="px-2 py-1 rounded-full text-xs font-semibold ${Number(e.is_paid) ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'}">
                            ${Number(e.is_paid) ? 'Paid' : 'In Wallet'}
                        </span>
                    </td>
                    <td class="px-4 py-3 text-sm text-gray-500">${escapeHtml(e.created_at || '')}</td>
                </tr>
            `).join('');
        }

        function fillEarningsVendorFilter(vendors) {
            const sel = document.getElementById('earningsVendorFilter');
            if (!sel) return;
            const current = sel.value;
            sel.innerHTML = '<option value="0">All merchants</option>' + vendors.map(v =>
                `<option value="${v.id}">${escapeHtml(v.business_name || v.name || ('Merchant #' + v.id))}</option>`
            ).join('');
            sel.value = current;
        }

        function escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        document.addEventListener('DOMContentLoaded', function() {
            loadConfig();
            loadPendingBulk();
            loadPendingCancellations();
            loadPendingRefunds();
            loadVendors();
            loadVendorEarnings();
        });
    </script>
</div>
</div>
</body>
</html>
