package com.anytypeio.anytype.feature.pebble.ui.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class ChangeSetDetailViewModel @Inject constructor(
    private val changeStore: ChangeStore
) : ViewModel() {

    private val _changeSet = MutableStateFlow<ChangeSet?>(null)
    val changeSet: StateFlow<ChangeSet?> = _changeSet.asStateFlow()

    fun load(changeSetId: String) {
        viewModelScope.launch {
            _changeSet.value = changeStore.getChangeSet(changeSetId)
        }
    }
}
