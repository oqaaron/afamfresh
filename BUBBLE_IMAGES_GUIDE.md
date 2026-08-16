# Adding Images to Home Screen Bubbles

## Quick Overview
The bubble images (Groceries, Bulk Deals, Hot Sales, Promos, Flash Sales, Orders) are now set up to display PNG images instead of just icons. Follow this guide to add your custom images.

---

## Step 1: Prepare Your Images

**Image Requirements:**
- Format: PNG (with transparency recommended)
- Size: 256x256 pixels minimum (will be scaled to 48dp in the app)
- Filenames: Use EXACTLY these names:
  - `groceries.png`
  - `bulk_deals.png`
  - `hot_sales.png`
  - `promos.png`
  - `flash_sales.png`
  - `orders.png`

---

## Step 2: Place Images in the Project

### For Customer App (Recommended):
**Path:** `app/src/customer/res/drawable/`

**Create the folder if it doesn't exist:**
```bash
mkdir -p app/src/customer/res/drawable
```

**Then copy your PNG files:**
```bash
cp groceries.png app/src/customer/res/drawable/
cp bulk_deals.png app/src/customer/res/drawable/
cp hot_sales.png app/src/customer/res/drawable/
cp promos.png app/src/customer/res/drawable/
cp flash_sales.png app/src/customer/res/drawable/
cp orders.png app/src/customer/res/drawable/
```

---

## Step 3: Update HomeScreen to Use Images

Open `app/src/main/java/com/techaus/afamfresh/ui/screens/HomeScreen.kt`

**Find the bubble definitions** (around lines 218-277) and replace them like this:

### Before (with icons):
```kotlin
GlovoBubble(
    icon = Icons.Default.Spa,
    title = "Groceries",
    isSelected = selectedFilter == HomeFilter.All,
    onClick = { selectedFilter = HomeFilter.All }
)
```

### After (with images):
```kotlin
GlovoBubble(
    drawableRes = R.drawable.groceries,
    title = "Groceries",
    isSelected = selectedFilter == HomeFilter.All,
    onClick = { selectedFilter = HomeFilter.All }
)
```

**Apply this pattern to all 6 bubbles:**

```kotlin
// Row 1
GlovoBubble(
    drawableRes = R.drawable.groceries,
    title = "Groceries",
    isSelected = selectedFilter == HomeFilter.All,
    onClick = { selectedFilter = HomeFilter.All }
)
GlovoBubble(
    drawableRes = R.drawable.bulk_deals,
    title = "Bulk Deals",
    isSelected = false,
    onClick = onBulkClick
)
GlovoBubble(
    drawableRes = R.drawable.hot_sales,
    title = "Hot Sale",
    isSelected = selectedFilter == HomeFilter.HotSale,
    onClick = {
        selectedFilter = if (selectedFilter == HomeFilter.HotSale) HomeFilter.All else HomeFilter.HotSale
    }
)

// Row 2
GlovoBubble(
    drawableRes = R.drawable.promos,
    title = "Promos",
    isSelected = selectedFilter == HomeFilter.Promos,
    onClick = {
        selectedFilter = if (selectedFilter == HomeFilter.Promos) HomeFilter.All else HomeFilter.Promos
    }
)
GlovoBubble(
    drawableRes = R.drawable.flash_sales,
    title = "Flash Sales",
    isSelected = selectedFilter == HomeFilter.FlashSales,
    onClick = {
        selectedFilter = if (selectedFilter == HomeFilter.FlashSales) HomeFilter.All else HomeFilter.FlashSales
    }
)
GlovoBubble(
    drawableRes = R.drawable.orders,
    title = "Orders",
    isSelected = false,
    onClick = onOrdersClick
)

// Row 3
GlovoBubble(
    drawableRes = R.drawable.browse,  // Optional: add browse.png if you want
    title = "Browse",
    isSelected = false,
    onClick = onBrowseClick
)
```

---

## Step 4: Build and Test

```bash
./gradlew :app:assembleCustomerDebug
```

The images should now appear in the bubbles on your home screen!

---

## Troubleshooting

### "Unresolved reference: R.drawable.groceries"
- ✅ Check that the image files are in `app/src/customer/res/drawable/`
- ✅ Verify filenames are exactly: `groceries.png`, `bulk_deals.png`, etc. (lowercase, underscores)
- ✅ Try rebuilding: `./gradlew clean :app:assembleCustomerDebug`

### Images appear small or pixelated
- ✅ Use higher resolution source images (512x512 or larger)
- ✅ The app will scale to 48dp automatically

### Images don't show in the right colors
- ✅ If your images have colored backgrounds, that will show
- ✅ For best results, use transparent backgrounds (PNG with alpha channel)

### Want to use both icons and images?
The `GlovoBubble` function supports both! Use either:
- `drawableRes = R.drawable.groceries` (for images)
- `icon = Icons.Default.Spa` (for vector icons)
- Or neither (will show empty circle)

---

## File Locations Reference

```
afamfresh/
├── app/
│   └── src/
│       ├── customer/
│       │   └── res/
│       │       └── drawable/          ← PUT YOUR PNG FILES HERE
│       │           ├── groceries.png
│       │           ├── bulk_deals.png
│       │           ├── hot_sales.png
│       │           ├── promos.png
│       │           ├── flash_sales.png
│       │           ├── orders.png
│       │           └── browse.png (optional)
│       └── main/
│           └── java/
│               └── com/techaus/afamfresh/
│                   └── ui/screens/
│                       └── HomeScreen.kt  ← EDIT THIS FILE
```

---

## Advanced: Image Density Variants (Optional)

For different screen densities, Android supports:
- `drawable-mdpi/` - Medium density
- `drawable-hdpi/` - High density  
- `drawable-xhdpi/` - Extra high density
- `drawable-xxhdpi/` - Extra extra high density
- `drawable-xxxhdpi/` - Extra extra extra high density

Place your images in `app/src/customer/res/drawable-xxhdpi/` for best quality on most devices. Android will automatically scale down for lower densities.

---

## Live Preview

After adding images and building, the bubbles will display your PNG images directly in the circles. The images are automatically:
- Scaled to 48dp (with ContentScale.Fit to maintain aspect ratio)
- Centered in the circles
- Responsive to selection state (background color changes)

---

## Notes

✅ The location permission request for GPS should now work (added in previous updates)
✅ The "Pick Location on Map" button is fully functional
✅ Address form auto-fills with map-selected locations
✅ Browse bubble is centered below Flash Sales

Enjoy your enhanced UI! 🎨
