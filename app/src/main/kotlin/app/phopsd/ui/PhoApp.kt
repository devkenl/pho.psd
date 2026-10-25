package app.phopsd.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorEffect
import app.phopsd.editor.PickPurpose
import app.phopsd.editor.PickSource
import app.phopsd.editor.Screen
import app.phopsd.platform.EditorViewModel
import app.phopsd.ui.editor.EditorDialogs
import app.phopsd.ui.editor.EditorScreen
import app.phopsd.ui.home.HomeScreen
import app.phopsd.ui.theme.PhoColors
import kotlinx.coroutines.delay

/** Formats accepted by File ▸ Open / Place. Verified decoders: platform ImageDecoder. */
private val IMAGE_MIME_TYPES = arrayOf("image/jpeg", "image/png", "image/webp")

/**
 * `.phopsd` has no registered MIME type, so projects are picked from all files and
 * validated by content (the archive's `mimetype` entry).
 */
private val PROJECT_MIME_TYPES = arrayOf("*/*")

/** ACTION_CREATE_DOCUMENT with a per-launch MIME type: input = (suggested file name, MIME). */
private class CreateDocumentWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

@Composable
fun PhoApp(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val controller = vm.controller
    var pendingPurpose by rememberSaveable { mutableStateOf(PickPurpose.OpenAsDocument) }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onImagePicked(uri, pendingPurpose)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onImagePicked(uri, pendingPurpose)
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.onProjectPicked(uri)
    }
    val saveLocationPicker = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        vm.onSaveLocationPicked(uri)
    }
    val exportLocationPicker = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        vm.onExportLocationPicked(uri)
    }

    // Leaving the app (home, task switch, screen off) autosaves unsaved work immediately.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAppBackgrounded() }

    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is EditorEffect.PickImage -> {
                    pendingPurpose = effect.purpose
                    when (effect.source) {
                        PickSource.Documents -> documentPicker.launch(IMAGE_MIME_TYPES)
                        PickSource.Photos -> photoPicker.launch(
                            PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                .build(),
                        )
                    }
                }
                EditorEffect.PickProjectToOpen -> projectPicker.launch(PROJECT_MIME_TYPES)
                is EditorEffect.OpenProjectUri -> vm.openProject(effect.uri)
                // Not a registered type everywhere; octet-stream keeps the ".phopsd" name intact.
                is EditorEffect.PickSaveLocation -> saveLocationPicker.launch(effect.suggestedFileName to "application/octet-stream")
                is EditorEffect.SaveProject -> vm.saveProject(effect.link)
                is EditorEffect.PickExportLocation -> {
                    vm.prepareExport(effect.settings)
                    exportLocationPicker.launch(effect.suggestedFileName to effect.settings.format.mimeType)
                }
            }
        }
    }

    // Auto-dismiss notices; warnings and errors stay longer.
    val notice = state.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(if (notice.kind == app.phopsd.editor.NoticeKind.Info) 3_500 else 7_000)
            controller.dismissNotice(notice.id)
        }
    }

    BackHandler(enabled = state.screen == Screen.Editor) {
        controller.dispatch(CommandId.CloseDocument)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(state.screen) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(PhoColors.AppBackground)
            .safeDrawingPadding()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { KeyShortcuts.handle(it, controller) },
    ) {
        when (state.screen) {
            Screen.Home -> HomeScreen(state, vm)
            Screen.Editor -> EditorScreen(state, vm)
        }
        EditorDialogs(state, vm)
    }
}
