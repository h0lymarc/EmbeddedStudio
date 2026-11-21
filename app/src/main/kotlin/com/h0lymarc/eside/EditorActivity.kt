package com.h0lymarc.eside

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.h0lymarc.eside.manager.TextMateSetup
import com.h0lymarc.eside.ui.theme.EmbeddedStudioTheme

class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start loading themes/grammar right away, in parallel with building the UI
        TextMateSetup.warmUp(this)

        val template = intent.getStringExtra(EXTRA_TEMPLATE)
        val project = intent.getStringExtra(EXTRA_PROJECT)
        val requiredLibs = intent.getStringArrayExtra(EXTRA_REQUIRED_LIBS)

        setContent {
            EmbeddedStudioTheme {
                EditorScreen(
                    templateAsset = template,
                    initialProject = project,
                    requiredLibraries = requiredLibs?.toList().orEmpty(),
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_TEMPLATE = "template_asset"   // e.g. "templates/blank.ino"
        const val EXTRA_PROJECT = "project_path"      // open an existing project folder
        const val EXTRA_REQUIRED_LIBS = "required_libs" // String[] library names to auto-install
    }
}
