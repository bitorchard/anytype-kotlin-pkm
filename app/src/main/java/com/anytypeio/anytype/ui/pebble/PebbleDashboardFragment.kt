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
import com.anytypeio.anytype.feature.pebble.ui.dashboard.PebbleDashboardScreen
import com.anytypeio.anytype.feature.pebble.ui.dashboard.PebbleDashboardViewModel
import javax.inject.Inject

class PebbleDashboardFragment : BaseComposeFragment() {

    @Inject
    lateinit var factory: ViewModelProvider.Factory

    private val vm by viewModels<PebbleDashboardViewModel> { factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PebbleDashboardScreen(
                viewModel = vm,
                onNavigateToInputHistory = {
                    findNavController().navigate(R.id.action_pebbleHome_to_inputHistory)
                },
                onNavigateToApproval = {
                    findNavController().navigate(R.id.action_pebbleHome_to_approvalScreen)
                },
                onNavigateToChangeLog = {
                    findNavController().navigate(R.id.action_pebbleHome_to_changeLog)
                },
                onNavigateToSettings = {
                    findNavController().navigate(R.id.action_pebbleHome_to_pebbleSettings)
                },
                onNavigateToDebug = {
                    findNavController().navigate(R.id.action_pebbleHome_to_pebbleDebug)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    override fun inject() {
        componentManager().pebbleComponent.get().inject(this)
    }

    override fun releaseDependencies() {
        // Pebble component is application-scoped; nothing to release here.
    }
}
