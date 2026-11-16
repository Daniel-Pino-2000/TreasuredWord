package com.application.bibleapp.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.application.bibleapp.data.model.BibleSourceType
import com.application.bibleapp.data.model.SelectedBibleVersion
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.remote.RenderServerWake
import com.application.bibleapp.data.remote.SemanticSearchApi
import com.application.bibleapp.data.repository.AuthRepository
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.ReadingProgressRepository
import com.application.bibleapp.utils.NetworkUtils

class BibleViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BibleViewModel::class.java)) {

            val repository = BibleRepository(
                context = context,
                remote = HelloAoBibleDataSource(),
                daily = OurMannaBibleDataSource()
            )

            val appContext = context.applicationContext
            val phoneIsOnline = { NetworkUtils.isOnline(appContext) }
            val viewModel = BibleViewModel(
                repository = repository,
                authRepository = AuthRepository(context),
                readingProgressRepository = ReadingProgressRepository(),
                semanticSearchApi = SemanticSearchApi(phoneIsOnline = phoneIsOnline),
                phoneIsOnline = phoneIsOnline,
                serverWake = RenderServerWake
            )

            @Suppress("UNCHECKED_CAST")
            return viewModel as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}