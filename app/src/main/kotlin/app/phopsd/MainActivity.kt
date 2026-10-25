package app.phopsd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.phopsd.platform.EditorViewModel
import app.phopsd.ui.PhoApp
import app.phopsd.ui.theme.PhoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 37: the platform draws edge-to-edge; PhoApp pads with safeDrawingPadding().
        setContent {
            PhoTheme {
                PhoApp(viewModel)
            }
        }
    }
}
