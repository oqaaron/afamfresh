package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.repository.SurplusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Constructor confirmed by MainActivity.kt: SurplusViewModel(surplusRepository)
class SurplusViewModel(
    private val surplusRepository: SurplusRepository
) : ViewModel() {

    private val _listings = MutableStateFlow<List<SurplusListing>>(emptyList())
    val listings: StateFlow<List<SurplusListing>> = _listings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canRetry = MutableStateFlow(true)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    init {
        loadListings()
    }

    fun loadListings() {
        _isLoading.value = true
        _error.value = null
        surplusRepository.getPublicListings { listings, error ->
            _isLoading.value = false
            if (listings != null) {
                _listings.value = listings
            } else {
                _error.value = error?.userMessage ?: "Unable to load surplus deals"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }
}
