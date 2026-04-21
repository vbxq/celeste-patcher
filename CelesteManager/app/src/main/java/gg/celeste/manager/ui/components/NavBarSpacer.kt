package gg.celeste.manager.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.celeste.manager.utils.DimenUtils

@Composable
fun NavBarSpacer() {
    Spacer(
        modifier = Modifier.height(DimenUtils.navBarPadding)
    )
}