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
import com.anytypeio.anytype.core_utils.ui.BaseComposeFragment
import com.anytypeio.anytype.di.common.componentManager
import com.anytypeio.anytype.feature.pebble.ui.manual.ManualInputScreen
import com.anytypeio.anytype.feature.pebble.ui.manual.ManualInputViewModel
import javax.inject.Inject

class ManualInputFragment : BaseComposeFragment() {

    @Inject
    lateinit var factory: ViewModelProvider.Factory

    private val vm by viewModels<ManualInputViewModel> { factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ManualInputScreen(
                viewModel = vm,
                onBack = { findNavController().popBackStack() }
            )
        }
    }

    override fun inject() {
        componentManager().pebbleComponent.get().inject(this)
    }

    override fun releaseDependencies() {
        // Application-scoped component; nothing to release.
    }
}
