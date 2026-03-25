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
import androidx.navigation.fragment.navArgs
import com.anytypeio.anytype.core_utils.ui.BaseComposeFragment
import com.anytypeio.anytype.di.common.componentManager
import com.anytypeio.anytype.feature.pebble.ui.changelog.ChangeSetDetailScreen
import com.anytypeio.anytype.feature.pebble.ui.changelog.ChangeSetDetailViewModel
import javax.inject.Inject

class ChangeSetDetailFragment : BaseComposeFragment() {

    @Inject lateinit var factory: ViewModelProvider.Factory
    private val vm by viewModels<ChangeSetDetailViewModel> { factory }

    private val changeSetId: String by lazy {
        arguments?.getString("changeSetId") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ChangeSetDetailScreen(
                changeSetId = changeSetId,
                viewModel = vm,
                onBack = { findNavController().popBackStack() }
            )
        }
    }

    override fun inject() { componentManager().pebbleComponent.get().inject(this) }
    override fun releaseDependencies() { }
}
