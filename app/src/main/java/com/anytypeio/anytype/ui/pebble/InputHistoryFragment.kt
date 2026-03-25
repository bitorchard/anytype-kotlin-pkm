package com.anytypeio.anytype.ui.pebble

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.anytypeio.anytype.R
import com.anytypeio.anytype.core_utils.ui.BaseComposeFragment
import com.anytypeio.anytype.di.common.componentManager
import com.anytypeio.anytype.feature.pebble.ui.history.InputHistoryScreen
import com.anytypeio.anytype.feature.pebble.ui.history.InputHistoryViewModel
import javax.inject.Inject

class InputHistoryFragment : BaseComposeFragment() {

    @Inject lateinit var factory: ViewModelProvider.Factory
    private val vm by viewModels<InputHistoryViewModel> { factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            InputHistoryScreen(
                viewModel = vm,
                onNavigateToChangeSet = { changeSetId ->
                    val args = android.os.Bundle().apply { putString("changeSetId", changeSetId) }
                    findNavController().navigate(R.id.action_inputHistory_to_changeSetDetail, args)
                },
                onBack = { findNavController().popBackStack() }
            )
        }
    }

    override fun inject() { componentManager().pebbleComponent.get().inject(this) }
    override fun releaseDependencies() { }
}
