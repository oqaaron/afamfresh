class WishlistViewModel : ViewModel() {
    private val _favoriteIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()

    private val _wishlistProducts = MutableStateFlow<List<Product>>(emptyList())
    val wishlistProducts: StateFlow<List<Product>> = _wishlistProducts.asStateFlow()

    fun toggleFavorite(userId: Int, productId: Int) {
        val currentFavorites = _favoriteIds.value.toMutableSet()
        if (currentFavorites.contains(productId)) {
            currentFavorites.remove(productId)
            // Call backend API to remove favorite
        } else {
            currentFavorites.add(productId)
            // Call backend API to add favorite
        }
        _favoriteIds.value = currentFavorites
    }
}