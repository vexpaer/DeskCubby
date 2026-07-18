package com.deskcubby.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.repository.DailyPoem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    diaryIndexDao: DiaryIndexDao,
    thoughtRepository: ThoughtRepository,
    private val poetryRepository: PoetryRepository,
) : ViewModel() {
    val diaries: StateFlow<List<DiaryIndexEntity>> = diaryIndexDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val thoughts: StateFlow<List<FlashThoughtEntity>> = thoughtRepository.recent.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val poem: StateFlow<DailyPoem> = poetryRepository.poem.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PoetryRepository.FALLBACK,
    )

    init {
        refreshPoem(force = false)
    }

    fun refreshPoem(force: Boolean = true) {
        viewModelScope.launch { runCatching { poetryRepository.refresh(force) } }
    }
}
