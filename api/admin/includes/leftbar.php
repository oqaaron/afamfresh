<!-- ============================================================
     leftbar.php — AfamFresh Admin Sidebar
     FIX: No markup changes required here.
          All sidebar display fixes are in admin.css and main.js.
          Submenus now driven purely by CSS max-height transition
          (jQuery slideUp/slideDown removed from main JS).
     ============================================================ -->
<nav class="ts-sidebar" id="adminSidebar" aria-label="Admin navigation">
  <ul class="ts-sidebar-menu">

    <li class="ts-label">Main</li>

    <li>
      <a href="dashboard.php">
        <i class="fa fa-dashboard" aria-hidden="true"></i> Dashboard
      </a>
    </li>

    <li class="has-submenu">
      <a href="#">
        <i class="fa fa-files-o" aria-hidden="true"></i> Category
        <i class="fa fa-angle-down more-icon" aria-hidden="true"></i>
      </a>
      <ul>
        <li><a href="add-category.php">Add Category</a></li>
        <li><a href="manage-category.php">Manage Category</a></li>
      </ul>
    </li>

    <li class="has-submenu">
      <a href="#">
        <i class="fa fa-files-o" aria-hidden="true"></i> Items
        <i class="fa fa-angle-down more-icon" aria-hidden="true"></i>
      </a>
      <ul>
        <li><a href="add-items.php">Add Items</a></li>
        <li><a href="manage-items.php">Manage Items</a></li>
      </ul>
    </li>

    <li class="has-submenu">
      <a href="#">
        <i class="fa fa-gift" aria-hidden="true"></i> Gift Hampers
        <i class="fa fa-angle-down more-icon" aria-hidden="true"></i>
      </a>
      <ul>
        <li><a href="add-gift-hamper.php">Add Gift Hamper</a></li>
        <li><a href="manage-gift-hampers.php">Manage Gift Hampers</a></li>
      </ul>
    </li>

    <li>
      <a href="manage-orders.php">
        <i class="fa fa-cart-arrow-down" aria-hidden="true"></i> Orders
      </a>
    </li>

    <li>
      <a href="manage-user.php">
        <i class="fa fa-user" aria-hidden="true"></i> Users
      </a>
    </li>

    <li>
      <a href="manage-user-roles.php">
        <i class="fa fa-users" aria-hidden="true"></i> User Roles
      </a>
    </li>

    <li class="has-submenu">
      <a href="#">
        <i class="fa fa-cog" aria-hidden="true"></i> Vendors
        <i class="fa fa-angle-down more-icon" aria-hidden="true"></i>
      </a>
      <ul>
        <li><a href="add-vendor.php">Add Vendor</a></li>
        <li><a href="manage-vendors.php">Manage Vendors</a></li>
        <li><a href="manage-vendor-stocks.php">Vendor Stocks</a></li>
      </ul>
    </li>

    <!-- Meal Plans - Admin only -->
    <li>
      <a href="manage-meal-plans.php">
        <i class="fa fa-cutlery" aria-hidden="true"></i> Meal Plans
      </a>
    </li>

    <li>
      <a href="manage-riders.php">
        <i class="fa fa-motorcycle" aria-hidden="true"></i> Riders
      </a>
    </li>

    <li>
      <a href="manage-delivery-pricing.php">
        <i class="fa fa-truck" aria-hidden="true"></i> Delivery Pricing
      </a>
    </li>

    <li>
      <a href="manage-promo-banner.php">
        <i class="fa fa-image" aria-hidden="true"></i> Promo Banner
      </a>
    </li>

    <li>
      <a href="manage-hero-section.php">
        <i class="fa fa-home" aria-hidden="true"></i> Hero Section
      </a>
    </li>

    <li>
      <a href="notification.php">
        <i class="fa fa-bell" aria-hidden="true"></i> Notification
      </a>
    </li>

    <li>
      <a href="send-user-notifications.php">
        <i class="fa fa-paper-plane" aria-hidden="true"></i> User Notifications
      </a>
    </li>

    <li>
      <a href="message.php">
        <i class="fa fa-envelope" aria-hidden="true"></i> Messages
      </a>
    </li>

  </ul>

  <p class="sidebar-footer">© Techaus 2026.</p>
</nav>