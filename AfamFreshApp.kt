package com.techaus.afamfresh

import android.app.Application
import com.techaus.afamfresh.viewmodel.AppViewModelFactory

class AfamFreshApp : Application() {
    val viewModelFactory by lazy { AppViewModelFactory(this) }

    override fun onCreate() {
        super.onCreate()
    }
}