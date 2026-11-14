package ai.airlink

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.airlink.ui.AirlinkScreen
import ai.airlink.ui.AirlinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: LinkViewModel = viewModel()
            val state by vm.state.collectAsState()

            AirlinkTheme(darkTheme = state.darkTheme) {
                // Keep the status/navigation bar icons readable in both themes.
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !state.darkTheme
                            isAppearanceLightNavigationBars = !state.darkTheme
                        }
                    }
                }
                AirlinkScreen(vm = vm)
            }
        }
    }
}
