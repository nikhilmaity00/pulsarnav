package com.pulsarnav.app.ui

import android.content.ContentResolver
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.pulsarnav.app.R
import com.pulsarnav.app.data.DataVaultManager
import com.pulsarnav.app.data.VaultPassphraseRequiredException
import com.pulsarnav.app.databinding.BottomSheetDataVaultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class DataVaultUiController(
    private val activity: AppCompatActivity,
    private val rootViewProvider: () -> View,
    private val themeProvider: () -> CockpitTheme,
    private val createThemedEditText: (String?, String?, Int?) -> EditText,
    private val launchVaultPickers: () -> Unit,
    private val onRefreshAfterImport: () -> Unit,
    private val onHaptic: () -> Unit,
    private val onFeedback: (String, Boolean) -> Unit
) {

    companion object {
        private const val MAX_VAULT_IMPORT_BYTES = 5 * 1024 * 1024
    }

    fun show(openQrDirectly: Boolean = false) {
        val theme = themeProvider()
        val dialog = BottomSheetDialog(activity, R.style.ObsidianBottomSheetDialog)
        val sheetBinding = BottomSheetDataVaultBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(sheetBinding.root)

        sheetBinding.btnSheetExportVault.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        sheetBinding.btnSheetExportVault.setTextColor(theme.onPrimaryColor)
        sheetBinding.btnSheetScanQr.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        sheetBinding.btnSheetScanQr.setTextColor(theme.accentColor)

        fun loadAndShowQr(includeSensitiveProfile: Boolean = false, passphrase: String? = null) {
            activity.lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.Default) {
                        DataVaultManager.getInstance(activity).generateSetupQrBitmap(
                            width = 600,
                            height = 600,
                            includeSensitiveProfile = includeSensitiveProfile,
                            passphrase = passphrase
                        )
                    }
                    if (!dialog.isShowing) return@launch
                    sheetBinding.ivVaultQrCode.setImageBitmap(bitmap)
                    sheetBinding.layoutQrContainer.visibility = View.VISIBLE
                } catch (e: Exception) {
                    onFeedback("QR Generation failed: ${e.message}", true)
                }
            }
        }

        if (openQrDirectly) {
            sheetBinding.layoutQrContainer.visibility = View.VISIBLE
            dialog.setOnShowListener { loadAndShowQr(includeSensitiveProfile = false, passphrase = null) }
        }

        sheetBinding.btnSheetShowQr.setOnClickListener {
            onHaptic()
            if (sheetBinding.layoutQrContainer.visibility == View.VISIBLE) {
                sheetBinding.layoutQrContainer.visibility = View.GONE
            } else {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Migration QR Privacy")
                    .setMessage(
                        "Choose whether to generate a safe Bike & Theme QR (no personal/medical details) " +
                            "or an AES-256-GCM Encrypted Full Profile QR."
                    )
                    .setPositiveButton("Bike Settings Only") { _, _ ->
                        loadAndShowQr(includeSensitiveProfile = false, passphrase = null)
                    }
                    .setNeutralButton("Full Encrypted QR") { _, _ ->
                        val layout = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(48, 20, 48, 10)
                        }
                        val etPass = createThemedEditText(
                            "QR Encryption Passphrase",
                            null,
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        )
                        layout.addView(etPass)
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("Encrypt Migration QR")
                            .setView(layout)
                            .setPositiveButton("Generate Encrypted QR") { _, _ ->
                                val pass = etPass.text.toString().trim()
                                if (pass.isBlank()) {
                                    onFeedback("Enter a passphrase to encrypt full profile QR.", true)
                                } else {
                                    loadAndShowQr(includeSensitiveProfile = true, passphrase = pass)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        sheetBinding.btnSheetScanQr.setOnClickListener {
            onHaptic()
            dialog.dismiss()
            launchGoogleCodeScanner()
        }

        sheetBinding.btnSheetExportVault.setOnClickListener {
            onHaptic()
            dialog.dismiss()
            rootViewProvider().postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showExportVaultPassphraseDialog()
                }
            }, 250)
        }

        sheetBinding.btnSheetImportVault.setOnClickListener {
            onHaptic()
            dialog.dismiss()
            rootViewProvider().postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    launchVaultPickers()
                }
            }, 250)
        }

        dialog.show()
    }

    private fun showExportVaultPassphraseDialog() {
        val theme = themeProvider()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(activity).apply {
            text = "Recommended: enter a strong passphrase to encrypt your .pnav backup with AES-256-GCM before sharing."
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPassphrase = createThemedEditText(
            "Encryption passphrase",
            null,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPassphrase)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Export Data Vault")
            .setView(layout)
            .setPositiveButton("Export") { _, _ ->
                val rawPass = etPassphrase.text.toString().trim()
                if (rawPass.isNotEmpty()) {
                    exportVaultFile(passphrase = rawPass, allowUnencrypted = false)
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Export Unencrypted Backup?")
                        .setMessage(
                            "Without a passphrase, the exported .pnav.json file stores emergency contacts, medical notes, " +
                                "home/work addresses, parked coordinates, and service history in plaintext."
                        )
                        .setPositiveButton("Encrypt Instead") { _, _ ->
                            showExportVaultPassphraseDialog()
                        }
                        .setNeutralButton("Export Plaintext") { _, _ ->
                            exportVaultFile(passphrase = null, allowUnencrypted = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportVaultFile(passphrase: String? = null, allowUnencrypted: Boolean = false) {
        activity.lifecycleScope.launch {
            try {
                Log.i("PulsarNav", "Exporting vault file...")
                val manager = DataVaultManager.getInstance(activity)
                val file = withContext(Dispatchers.IO) {
                    manager.exportVaultToFile(passphrase, allowUnencrypted = allowUnencrypted)
                }
                Log.i("PulsarNav", "Vault file created: ${file.absolutePath}, size=${file.length()} bytes")
                val chooser = manager.createVaultShareIntent(file)
                activity.startActivity(chooser)
                onFeedback(
                    if (passphrase != null) "Encrypted .pnav vault exported" else "Plaintext .pnav.json vault exported",
                    false
                )
            } catch (e: Throwable) {
                Log.e("PulsarNav", "Export failed", e)
                onFeedback("Export failed: ${e.message}", true)
            }
        }
    }

    private fun readBoundedText(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_VAULT_IMPORT_BYTES) {
                throw IllegalArgumentException("Vault file exceeds ${MAX_VAULT_IMPORT_BYTES / (1024 * 1024)} MB limit")
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend fun readTextFromUriSafe(uri: Uri): String? = withContext(Dispatchers.IO) {
        Log.i("PulsarNav", "Reading vault text from URI: $uri, scheme: ${uri.scheme}")
        try {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                val content = readBoundedText(stream)
                if (content.isNotBlank()) return@withContext content
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            Log.w("PulsarNav", "openInputStream failed for $uri: ${e.message}")
        }

        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val filePath = uri.path
            if (!filePath.isNullOrBlank()) {
                try {
                    val file = File(filePath)
                    if (file.exists() && file.canRead()) {
                        file.inputStream().use { stream ->
                            val content = readBoundedText(stream)
                            if (content.isNotBlank()) return@withContext content
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("PulsarNav", "Direct file read failed for $filePath: ${e.message}")
                }
            }
        }

        try {
            activity.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    val content = readBoundedText(stream)
                    if (content.isNotBlank()) return@withContext content
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            Log.w("PulsarNav", "openFileDescriptor failed: ${e.message}")
        }

        null
    }

    fun showExternalImportConfirmation(uri: Uri) {
        val fileName = try {
            uri.lastPathSegment?.substringAfterLast('/') ?: "Data Vault Backup"
        } catch (_: Exception) {
            "Data Vault Backup"
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restore Data Vault?")
            .setMessage("Do you want to restore motorcycle settings, trips, and fuel logs from '$fileName' into PulsarNav?")
            .setPositiveButton("Restore") { _, _ ->
                importFromUri(uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDecryptVaultPassphraseDialog(rawContent: String) {
        val theme = themeProvider()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(activity).apply {
            text = "This .pnav backup is encrypted. Enter your passphrase to restore:"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPassphrase = createThemedEditText(
            "Passphrase",
            null,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPassphrase)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Unlock Encrypted Vault")
            .setView(layout)
            .setPositiveButton("Unlock & Restore") { _, _ ->
                executeVaultImport(rawContent, etPassphrase.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeVaultImport(rawContent: String, passphrase: String?) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                DataVaultManager.getInstance(activity).importVaultFromInput(rawContent, passphrase)
            }
            result.onSuccess { summary ->
                val name = summary.riderName.ifBlank { summary.bikeName.ifBlank { "Bike" } }
                onFeedback(
                    "Vault Restored: ${summary.tripsImported} trips, ${summary.fuelEntriesImported} fuel logs for $name",
                    false
                )
                try {
                    onRefreshAfterImport()
                } catch (e: Exception) {
                    Log.e("PulsarNav", "Error refreshing UI after vault import", e)
                }
            }.onFailure { err ->
                if (err is VaultPassphraseRequiredException) {
                    showDecryptVaultPassphraseDialog(rawContent)
                } else {
                    Log.e("PulsarNav", "Vault import failed", err)
                    onFeedback("Import failed: ${err.message ?: "Invalid vault file"}", true)
                }
            }
        }
    }

    fun importFromUri(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                Log.i("PulsarNav", "Importing vault from URI: $uri")
                val rawContent = readTextFromUriSafe(uri)
                if (!rawContent.isNullOrBlank()) {
                    executeVaultImport(rawContent, passphrase = null)
                } else {
                    onFeedback("Could not read backup file. Please select the file using 'Import File'.", true)
                }
            } catch (e: Exception) {
                Log.e("PulsarNav", "Error importing vault", e)
                onFeedback("Import error: ${e.message}", true)
            }
        }
    }

    private fun executeQrImport(rawValue: String, passphrase: String? = null) {
        val result = DataVaultManager.getInstance(activity).importFromQrPayload(rawValue, passphrase)
        result.onSuccess { msg ->
            onFeedback(msg, false)
            onRefreshAfterImport()
        }.onFailure { err ->
            if (err is VaultPassphraseRequiredException) {
                showDecryptQrPassphraseDialog(rawValue)
            } else {
                onFeedback("QR Error: ${err.message}", true)
            }
        }
    }

    private fun showDecryptQrPassphraseDialog(rawQrPayload: String) {
        val theme = themeProvider()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }
        val tvDesc = TextView(activity).apply {
            text = "This migration QR is encrypted. Enter its passphrase to restore:"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 14)
        }
        val etPass = createThemedEditText(
            "QR Passphrase",
            null,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        layout.addView(tvDesc)
        layout.addView(etPass)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Unlock Migration QR")
            .setView(layout)
            .setPositiveButton("Unlock") { _, _ ->
                executeQrImport(rawQrPayload, etPass.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchGoogleCodeScanner() {
        try {
            val scanner = GmsBarcodeScanning.getClient(activity)
            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        executeQrImport(rawValue)
                    }
                }
                .addOnFailureListener { e: Exception ->
                    onFeedback("Scan cancelled: ${e.message}", true)
                }
        } catch (e: Exception) {
            onFeedback("Google Code Scanner not available: ${e.message}", true)
        }
    }
}
