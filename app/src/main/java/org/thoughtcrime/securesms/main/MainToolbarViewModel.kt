/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlowable
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.calls.log.CallLogFilter
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient

class MainToolbarViewModel : ViewModel() {
  private val internalStateFlow = MutableStateFlow(MainToolbarState())
  private val internalEvents = MutableSharedFlow<Event>()

  val state: StateFlow<MainToolbarState> = internalStateFlow

  fun refresh() {
    internalStateFlow.update {
      it.copy(
        self = Recipient.self(),
        hasFailedBackups = BackupRepository.shouldDisplayBackupFailedIndicator() || BackupRepository.shouldDisplayBackupAlreadyRedeemedIndicator(),
        hasPassphrase = !SignalStore.settings.passphraseDisabled
      )
    }
  }

  fun emitEvent(event: Event) {
    viewModelScope.launch {
      internalEvents.emit(event)
    }
  }

  fun setToolbarColor(@ColorInt color: Int) {
    internalStateFlow.update {
      it.copy(toolbarColor = Color(color))
    }
  }

  fun setSearchQuery(query: String) {
    internalStateFlow.update {
      it.copy(searchQuery = query)
    }

    viewModelScope.launch {
      internalEvents.emit(Event.Search.Query(query))
    }
  }

  @JvmOverloads
  fun setToolbarMode(mode: MainToolbarMode, destination: MainNavigationDestination? = null) {
    val previousMode = internalStateFlow.value.mode

    internalStateFlow.update {
      it.copy(mode = mode, destination = destination ?: it.destination, searchQuery = "")
    }

    emitPossibleSearchStateChangeEvent(previousMode, mode)
  }

  fun setProxyState(proxyState: MainToolbarState.ProxyState) {
    internalStateFlow.update {
      it.copy(proxyState = proxyState)
    }
  }

  fun setNotificationProfileEnabled(hasEnabledNotificationProfile: Boolean) {
    internalStateFlow.update {
      it.copy(hasEnabledNotificationProfile = hasEnabledNotificationProfile)
    }
  }

  fun setShowNotificationProfilesTooltip(showNotificationProfilesTooltip: Boolean) {
    internalStateFlow.update {
      it.copy(showNotificationProfilesTooltip = showNotificationProfilesTooltip)
    }
  }

  fun setHasUnreadPayments(hasUnreadPayments: Boolean) {
    internalStateFlow.update {
      it.copy(hasUnreadPayments = hasUnreadPayments)
    }
  }

  fun setChatFilter(conversationFilter: ConversationFilter) {
    internalStateFlow.update {
      it.copy(chatFilter = conversationFilter)
    }

    viewModelScope.launch {
      when (conversationFilter) {
        ConversationFilter.UNREAD -> internalEvents.emit(Event.Chats.ApplyFilter)
        else -> internalEvents.emit(Event.Chats.ClearFilter)
      }
    }
  }

  fun setCallLogFilter(callLogFilter: CallLogFilter) {
    internalStateFlow.update {
      it.copy(callFilter = callLogFilter)
    }

    viewModelScope.launch {
      when (callLogFilter) {
        CallLogFilter.MISSED -> internalEvents.emit(Event.CallLog.ApplyFilter)
        else -> internalEvents.emit(Event.CallLog.ClearFilter)
      }
    }
  }

  fun getSearchEventsFlowable(): Flowable<Event.Search> {
    return internalEvents.filterIsInstance(Event.Search::class).asFlowable()
  }

  fun getCallLogEventsFlowable(): Flowable<Event.CallLog> {
    return internalEvents.filterIsInstance(Event.CallLog::class).asFlowable()
  }

  fun getChatEventsFlowable(): Flowable<Event.Chats> {
    return internalEvents.filterIsInstance(Event.Chats::class).asFlowable()
  }

  fun clearCallHistory() {
    viewModelScope.launch {
      internalEvents.emit(Event.CallLog.ClearHistory)
    }
  }

  fun markAllMessagesRead() {
    MainToolbarRepository.markAllMessagesRead()
  }

  fun setSearchHint(@StringRes hint: Int) {
    internalStateFlow.update {
      it.copy(searchHint = hint)
    }
  }

  private fun emitPossibleSearchStateChangeEvent(previousMode: MainToolbarMode, mode: MainToolbarMode) {
    if (previousMode == MainToolbarMode.SEARCH && mode != MainToolbarMode.SEARCH) {
      emitEvent(Event.Search.Close)
    } else if (mode == MainToolbarMode.SEARCH && previousMode != MainToolbarMode.SEARCH) {
      emitEvent(Event.Search.Open)
    }
  }

  sealed interface Event {
    sealed interface Search : Event {
      data object Open : Search
      data object Close : Search
      data class Query(val query: String) : Search
    }

    sealed interface Chats : Event {
      data object ApplyFilter : Chats
      data object ClearFilter : Chats
      data object CloseArchive : Chats
    }

    sealed interface CallLog : Event {
      data object ApplyFilter : CallLog
      data object ClearFilter : CallLog
      data object ClearHistory : CallLog
    }
  }
}
