<!-- ============================================================
     header.php  — AfamFresh Admin
     FIX 1: Hamburger button is now real HTML (not JS-injected),
             with the correct class .dash-hamburger
     FIX 2: Mobile profile nav restored inside a slide-down panel
     FIX 3: Removed the old .menu-btn <span> (unused in admin)
     ============================================================ -->
<header class="ts-header">

  <!-- Hamburger — visible only on tablet/mobile via CSS -->
  <button class="dash-hamburger" id="dashHamburger" aria-label="Toggle sidebar" aria-expanded="false">
    <span></span>
  </button>

  <h4 class="text-white">
    <i class="fa fa-leaf"></i>&nbsp; AfamFresh Admin-Dashboard
  </h4>

  <ul class="ts-profile-nav">
    <li class="ts-account">
      <a href="#" aria-haspopup="true">
        <i class="fa fa-user-circle-o ts-avatar hidden-side" style="font-size:24px;"></i>
        Account
        <i class="fa fa-angle-down hidden-side"></i>
      </a>
      <ul>
        <li><a href="change-password.php"><i class="fa fa-key"></i> Change Password</a></li>
        <li><a href="logout.php"><i class="fa fa-sign-out"></i> Logout</a></li>
      </ul>
    </li>
  </ul>

</header>
