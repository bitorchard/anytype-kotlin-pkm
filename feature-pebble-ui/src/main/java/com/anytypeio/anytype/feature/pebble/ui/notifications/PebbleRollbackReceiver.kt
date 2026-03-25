package com.anytypeio.anytype.feature.pebble.ui.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeRollback
import com.anytypeio.anytype.pebble.changecontrol.model.ConflictResolution
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that handles the "Undo" action from auto-applied notifications.
 * Triggers [ChangeRollback] for the specified change set.
 *
 * Must be registered in the app module manifest and wired into the DI graph.
 */
class PebbleRollbackReceiver : BroadcastReceiver() {

    @Inject lateinit var changeStore: ChangeStore
    @Inject lateinit var changeRollback: ChangeRollback
    @Inject lateinit var errorNotification: PebbleErrorNotification

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val changeSetId = intent.getStringExtra(EXTRA_CHANGE_SET_ID) ?: return
        scope.launch {
            val cs = changeStore.getChangeSet(changeSetId) ?: run {
                Timber.w("[Pebble] PebbleRollbackReceiver: change set $changeSetId not found")
                return@launch
            }
            val result = changeRollback.rollback(cs, resolution = ConflictResolution.SKIP)
            Timber.i("[Pebble] PebbleRollbackReceiver: rollback result=${result::class.simpleName}")
            errorNotification.cancel(PebbleErrorType.AUTO_APPLIED)
        }
    }

    companion object {
        const val EXTRA_CHANGE_SET_ID = "change_set_id"
    }
}
