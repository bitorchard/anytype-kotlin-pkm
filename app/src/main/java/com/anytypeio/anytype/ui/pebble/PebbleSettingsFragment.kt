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
import com.anytypeio.anytype.feature.pebble.ui.settings.PebbleSettingsScreen
import com.anytypeio.anytype.feature.pebble.ui.settings.PebbleSettingsViewModel
import javax.inject.Inject

class PebbleSettingsFragment : BaseComposeFragment() {

    @Inject lateinit var factory: ViewModelProvider.Factory
    private val vm by viewModels<PebbleSettingsViewModel> { factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PebbleSettingsScreen(
                viewModel = vm,
                onNavigateToQr = {
                    findNavController().navigate(R.id.action_pebbleSettings_to_webhookQr)
                },
                onNavigateToDebug = {
                    findNavController().navigate(R.id.action_pebbleSettings_to_pebbleDebug)
                },
                onBack = { findNavController().popBackStack() }
            )
        }
    }

    override fun inject() { componentManager().pebbleComponent.get().inject(this) }
    override fun releaseDependencies() { }
}
