package com.musico

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.musico.store.AppContainerProvider
import com.musico.ui.MusicoApp
import com.musico.ui.theme.MusicoTheme

class MainActivity : ComponentActivity() {

    private var pendingFolderCallback: ((Uri) -> Unit)? = null
    private var pendingLyricsCallback: ((Uri) -> Unit)? = null
    private var pendingImageCallback: ((Uri) -> Unit)? = null
    private var pendingExtensionCallback: ((Uri) -> Unit)? = null

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val callback = pendingFolderCallback
        pendingFolderCallback = null
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            callback?.invoke(uri)
        }
    }

    private val openLyricsDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callback = pendingLyricsCallback
        pendingLyricsCallback = null
        if (uri != null) {
            callback?.invoke(uri)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val callback = pendingImageCallback
        pendingImageCallback = null
        if (uri != null) {
            callback?.invoke(uri)
        }
    }

    private val openExtensionDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callback = pendingExtensionCallback
        pendingExtensionCallback = null
        if (uri != null) {
            callback?.invoke(uri)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val appContainer = (application as AppContainerProvider).appContainer

        setContent {
            MusicoTheme {
                MusicoApp(
                    appContainer = appContainer,
                    onPickFolder = { onPicked ->
                        pendingFolderCallback = onPicked
                        openDocumentTreeLauncher.launch(null)
                    },
                    onPickLyricsFile = { onPicked ->
                        pendingLyricsCallback = onPicked
                        openLyricsDocumentLauncher.launch(
                            arrayOf("*/*")
                        )
                    },
                    onPickImage = { onPicked ->
                        pendingImageCallback = onPicked
                        pickImageLauncher.launch("image/*")
                    },
                    onPickExtensionFile = { onPicked ->
                        pendingExtensionCallback = onPicked
                        openExtensionDocumentLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val appContainer = (application as AppContainerProvider).appContainer
        appContainer.playerController.persistPosition(flush = true)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
