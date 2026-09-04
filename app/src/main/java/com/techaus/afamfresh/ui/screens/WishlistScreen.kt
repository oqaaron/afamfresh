@Composable
fun WishlistScreen(
    wishlistProducts: List<Product>,
    onProductClick: (Product) -> Unit,
    onRemoveFavorite: (Product) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("My Wishlist") }) }
    ) { padding ->
        if (wishlistProducts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No saved items yet. Tap the heart icon on any product to save it!")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding)
            ) {
                items(wishlistProducts) { product ->
                    ProductCard(
                        product = product,
                        isFavorite = true,
                        onFavoriteClick = { onRemoveFavorite(product) }
                    )
                }
            }
        }
    }
}