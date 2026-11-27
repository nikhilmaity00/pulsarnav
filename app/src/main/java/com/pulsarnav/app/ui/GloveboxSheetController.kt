package com.pulsarnav.app.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsarnav.app.R
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.databinding.BottomSheetDigitalGloveboxBinding
import com.pulsarnav.app.maintenance.GloveboxDocumentStatus
import com.pulsarnav.app.maintenance.GloveboxExpiryEvaluator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class GloveboxSheetController(
    private val activity: android.app.Activity,
    private val appSettings: AppSettings,
    private val themeProvider: () -> CockpitTheme,
    private val createThemedEditText: (String?, String?, Int?) -> EditText,
    private val launchDocumentPicker: (docType: String) -> Unit,
    private val onDocumentsChanged: () -> Unit,
    private val onBikeInfoChanged: () -> Unit,
    private val onRecordSyncTime: () -> Unit,
    private val onHaptic: () -> Unit,
    private val onFeedback: (String, Boolean) -> Unit
) {

    private var activeSheet: BottomSheetDialog? = null
    private var activeBinding: BottomSheetDigitalGloveboxBinding? = null

    fun show() {
        val sheet = BottomSheetDialog(activity, R.style.ObsidianBottomSheetDialog)
        val sheetBinding = BottomSheetDigitalGloveboxBinding.inflate(LayoutInflater.from(activity))
        sheet.setContentView(sheetBinding.root)

        activeSheet = sheet
        activeBinding = sheetBinding
        sheet.setOnDismissListener {
            activeSheet = null
            activeBinding = null
        }

        bindData(sheetBinding, sheet)
        sheet.show()
    }

    private fun bindData(
        sheetBinding: BottomSheetDigitalGloveboxBinding,
        sheet: BottomSheetDialog
    ) {
        val theme = themeProvider()

        // --- RC Document ---
        val rcNumber = appSettings.docRcNumber.ifBlank { "Not set" }
        val hasRcFile = appSettings.docRcPath.isNotBlank() && File(appSettings.docRcPath).exists()
        val rcFileName = if (hasRcFile) File(appSettings.docRcPath).name else "No document attached"

        sheetBinding.tvRcNumber.text = rcNumber
        sheetBinding.tvRcFileName.text = rcFileName
        sheetBinding.badgeRcStatus.apply {
            if (hasRcFile) {
                text = "ATTACHED"
                setTextColor(ContextCompat.getColor(activity, R.color.status_connected))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.status_connected_subtle))
            } else {
                text = "NO FILE"
                setTextColor(ContextCompat.getColor(activity, R.color.text_tertiary))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.surface_elevated))
            }
        }
        sheetBinding.btnViewRc.apply {
            isEnabled = hasRcFile
            alpha = if (hasRcFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasRcFile) {
                    onHaptic()
                    viewDocument(appSettings.docRcPath, "Registration Certificate (RC)")
                }
            }
        }
        sheetBinding.btnUploadRc.setOnClickListener {
            onHaptic()
            launchDocumentPicker("rc")
        }
        sheetBinding.btnEditRc.setOnClickListener {
            onHaptic()
            showEditDocumentDetailsDialog("rc", "Registration Certificate (RC)", appSettings.docRcNumber, null)
        }

        // --- Insurance Document ---
        val insPolicy = appSettings.docInsurancePolicy.ifBlank { "Not set" }
        val insExpiry = appSettings.docInsuranceExpiry.ifBlank { "Expiry not set" }
        val hasInsFile = appSettings.docInsurancePath.isNotBlank() && File(appSettings.docInsurancePath).exists()

        sheetBinding.tvInsurancePolicy.text = insPolicy
        sheetBinding.tvInsuranceExpiry.text = insExpiry
        applyStatusBadge(
            badge = sheetBinding.badgeInsuranceStatus,
            hasFile = hasInsFile,
            rawExpiry = appSettings.docInsuranceExpiry,
            validLabel = "ACTIVE"
        )
        sheetBinding.btnViewInsurance.apply {
            isEnabled = hasInsFile
            alpha = if (hasInsFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasInsFile) {
                    onHaptic()
                    viewDocument(appSettings.docInsurancePath, "Comprehensive Insurance")
                }
            }
        }
        sheetBinding.btnUploadInsurance.setOnClickListener {
            onHaptic()
            launchDocumentPicker("insurance")
        }
        sheetBinding.btnEditInsurance.setOnClickListener {
            onHaptic()
            showEditDocumentDetailsDialog("insurance", "Comprehensive Insurance", appSettings.docInsurancePolicy, appSettings.docInsuranceExpiry)
        }

        // --- PUC Document ---
        val pucCert = appSettings.docPucCert.ifBlank { "Not set" }
        val pucExpiry = appSettings.docPucExpiry.ifBlank { "Expiry not set" }
        val hasPucFile = appSettings.docPucPath.isNotBlank() && File(appSettings.docPucPath).exists()

        sheetBinding.tvPucNumber.text = pucCert
        sheetBinding.tvPucExpiry.text = pucExpiry
        applyStatusBadge(
            badge = sheetBinding.badgePucStatus,
            hasFile = hasPucFile,
            rawExpiry = appSettings.docPucExpiry,
            validLabel = "VALID"
        )
        sheetBinding.btnViewPuc.apply {
            isEnabled = hasPucFile
            alpha = if (hasPucFile) 1.0f else 0.4f
            setOnClickListener {
                if (hasPucFile) {
                    onHaptic()
                    viewDocument(appSettings.docPucPath, "Pollution Certificate (PUC)")
                }
            }
        }
        sheetBinding.btnUploadPuc.setOnClickListener {
            onHaptic()
            launchDocumentPicker("puc")
        }
        sheetBinding.btnEditPuc.setOnClickListener {
            onHaptic()
            showEditDocumentDetailsDialog("puc", "Pollution Under Control (PUC)", appSettings.docPucCert, appSettings.docPucExpiry)
        }

        listOf(
            sheetBinding.cardGloveboxRc,
            sheetBinding.cardGloveboxInsurance,
            sheetBinding.cardGloveboxPuc
        ).forEach { card ->
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = theme.cardBorderColor
        }
        listOf(
            sheetBinding.btnViewRc,
            sheetBinding.btnViewInsurance,
            sheetBinding.btnViewPuc
        ).forEach { btn ->
            btn.setTextColor(theme.accentColor)
            btn.iconTint = ColorStateList.valueOf(theme.accentColor)
            btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
        }
        listOf(
            sheetBinding.btnUploadRc,
            sheetBinding.btnUploadInsurance,
            sheetBinding.btnUploadPuc,
            sheetBinding.btnEditRc,
            sheetBinding.btnEditInsurance,
            sheetBinding.btnEditPuc
        ).forEach { btn ->
            btn.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
        }
        sheetBinding.btnGloveboxClose.apply {
            backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
            setTextColor(theme.onPrimaryColor)
            setOnClickListener { sheet.dismiss() }
        }
    }

    private fun showEditDocumentDetailsDialog(
        docType: String,
        title: String,
        currentNum: String,
        currentExpiry: String?
    ) {
        val theme = themeProvider()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvNumLabel = TextView(activity).apply {
            text = if (docType == "insurance") "Policy Name / Number:" else "Document / Plate Number:"
            setTextColor(theme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNumLabel)

        val etNum = createThemedEditText(null, currentNum.ifBlank { numberOrPolicy(docType) }, null)
        layout.addView(etNum)

        val etExpiry = if (expiryRequired(docType)) {
            val tvExpLabel = TextView(activity).apply {
                text = "\nExpiry Date:"
                setTextColor(theme.textPrimaryColor)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            layout.addView(tvExpLabel)

            val et = createThemedEditText("e.g. 15 Nov 2027", currentExpiry ?: "", null)
            layout.addView(et)

            val btnPickExpiry = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Pick Date from Calendar"
                textSize = 12f
                setTextColor(theme.accentColor)
                strokeColor = ColorStateList.valueOf(theme.borderColor)
                setOnClickListener {
                    onHaptic()
                    val cal = Calendar.getInstance()
                    GloveboxExpiryEvaluator.parseExpiryDate(et.text.toString())?.let { existingDate ->
                        cal.set(existingDate.year, existingDate.monthValue - 1, existingDate.dayOfMonth)
                    }
                    DatePickerDialog(
                        activity,
                        { _, y, m, d ->
                            cal.set(y, m, d)
                            et.setText(SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(cal.time))
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            }
            layout.addView(btnPickExpiry)
            et
        } else null

        MaterialAlertDialogBuilder(activity)
            .setTitle("Edit $title")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newNum = etNum.text.toString().trim()
                val newExp = etExpiry?.text?.toString()?.trim()

                when (docType) {
                    "rc" -> {
                        if (newNum.isNotBlank()) {
                            appSettings.docRcNumber = newNum
                            appSettings.bikeRegNumber = newNum
                        }
                    }
                    "insurance" -> {
                        if (newNum.isNotBlank()) appSettings.docInsurancePolicy = newNum
                        if (newExp != null) appSettings.docInsuranceExpiry = newExp
                    }
                    "puc" -> {
                        if (newNum.isNotBlank()) appSettings.docPucCert = newNum
                        if (newExp != null) appSettings.docPucExpiry = newExp
                    }
                }

                onDocumentsChanged()
                onBikeInfoChanged()
                onRecordSyncTime()
                activeBinding?.let { b -> activeSheet?.let { s -> bindData(b, s) } }
                onFeedback("$title updated", false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyStatusBadge(
        badge: TextView,
        hasFile: Boolean,
        rawExpiry: String?,
        validLabel: String
    ) {
        val status = GloveboxExpiryEvaluator.evaluate(hasFile, rawExpiry)
        val (label, textColorRes, bgColorRes) = when (status) {
            GloveboxDocumentStatus.VALID ->
                Triple(validLabel, R.color.status_connected, R.color.status_connected_subtle)
            GloveboxDocumentStatus.EXPIRING_SOON ->
                Triple("EXPIRING", R.color.status_scanning, R.color.status_scanning_subtle)
            GloveboxDocumentStatus.EXPIRED ->
                Triple("EXPIRED", R.color.status_disconnected, R.color.status_disconnected_subtle)
            GloveboxDocumentStatus.ATTACHED_NO_EXPIRY ->
                Triple("ATTACHED", R.color.text_secondary, R.color.surface_elevated)
            GloveboxDocumentStatus.NO_FILE ->
                Triple("NO FILE", R.color.text_tertiary, R.color.surface_elevated)
        }
        badge.text = label
        badge.setTextColor(ContextCompat.getColor(activity, textColorRes))
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, bgColorRes))
    }

    private fun numberOrPolicy(docType: String): String = when (docType) {
        "rc" -> appSettings.docRcNumber
        "insurance" -> appSettings.docInsurancePolicy
        else -> appSettings.docPucCert
    }

    private fun expiryRequired(docType: String): Boolean =
        docType == "insurance" || docType == "puc"

    fun saveUploadedDocument(uri: Uri, docType: String?) {
        if (docType == null) return
        var targetFile: File? = null
        try {
            val mime = activity.contentResolver.getType(uri) ?: "application/octet-stream"
            val mappedExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val extension = when {
                !mappedExt.isNullOrBlank() && mappedExt.length <= 5 -> mappedExt.lowercase(Locale.US)
                mime.contains("pdf", ignoreCase = true) -> "pdf"
                mime.contains("png", ignoreCase = true) -> "png"
                mime.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val docsDir = File(activity.filesDir, "documents").apply { mkdirs() }
            val createdFile = File(docsDir, "${docType}_${System.currentTimeMillis()}.$extension")
            targetFile = createdFile

            val input = activity.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Unable to read selected document")
            input.use { stream ->
                createdFile.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }

            val oldPath = when (docType) {
                "rc" -> appSettings.docRcPath.also { appSettings.docRcPath = createdFile.absolutePath }
                "insurance" -> appSettings.docInsurancePath.also { appSettings.docInsurancePath = createdFile.absolutePath }
                "puc" -> appSettings.docPucPath.also { appSettings.docPucPath = createdFile.absolutePath }
                else -> null
            }

            if (!oldPath.isNullOrBlank() && oldPath != createdFile.absolutePath) {
                File(oldPath).takeIf { it.exists() }?.delete()
            }

            onRecordSyncTime()
            onDocumentsChanged()
            activeBinding?.let { b -> activeSheet?.let { s -> bindData(b, s) } }
            onHaptic()
            onFeedback("Document uploaded", false)
        } catch (e: Exception) {
            targetFile?.takeIf { it.exists() }?.delete()
            onFeedback("Failed to upload document: ${e.message}", true)
        }
    }

    private fun viewDocument(path: String, title: String) {
        val file = File(path)
        if (!file.exists()) {
            onFeedback("Document file not found on device", true)
            return
        }

        try {
            val fileUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.applicationContext.packageName}.fileprovider",
                file
            )
            val mimeType = if (file.name.endsWith(".pdf", ignoreCase = true)) {
                "application/pdf"
            } else {
                "image/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "View $title"))
        } catch (e: Exception) {
            onFeedback("Could not open document viewer: ${e.message}", true)
        }
    }
}
