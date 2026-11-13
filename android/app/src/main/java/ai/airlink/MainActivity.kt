package ai.airlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.airlink.ui.AirlinkScreen
import ai.airlink.ui.AirlinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirlinkTheme {
                AirlinkScreen(vm = viewModel())
            }
        }
    }
}
