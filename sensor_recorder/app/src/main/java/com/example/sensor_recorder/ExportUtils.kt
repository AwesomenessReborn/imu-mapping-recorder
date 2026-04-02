// ExportUtils.kt
package com.example.sensor_recorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportUtils {

    /**
     * Creates a ZIP file from the recording directory
     * Returns the File object for the created ZIP
     */
    fun zipRecordingDirectory(context: Context, recordingDir: File): File? {
        return try {
            val zipFileName = "${recordingDir.name}.zip"
            val cacheDir = context.cacheDir
            val zipFile = File(cacheDir, zipFileName)

            // Delete old ZIP if it exists
            if (zipFile.exists()) {
                zipFile.delete()
            }

            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                recordingDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        FileInputStream(file).use { input ->
                            val entry = ZipEntry(file.name)
                            zipOut.putNextEntry(entry)
                            input.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            Timber.d("Created ZIP: ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            Timber.e(e, "Error creating ZIP file")
            null
        }
    }

    /**
     * Share recording via Android share sheet
     */
    fun shareRecording(context: Context, recordingDir: File): Boolean {
        return try {
            val zipFile = zipRecordingDirectory(context, recordingDir)
            if (zipFile == null) {
                Timber.e("Failed to create ZIP file")
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "com.example.sensor_recorder.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "IMU Recording: ${recordingDir.name}")
                putExtra(Intent.EXTRA_TEXT, "IMU sensor recording from Sensor Recorder app")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Export Recording"))
            Timber.d("Share sheet opened successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sharing recording")
            false
        }
    }

    /**
     * Get a content URI for a recording ZIP file
     */
    fun getRecordingUri(context: Context, recordingDir: File): Uri? {
        return try {
            val zipFile = zipRecordingDirectory(context, recordingDir)
            if (zipFile == null) {
                Timber.e("Failed to create ZIP file")
                return null
            }

            FileProvider.getUriForFile(
                context,
                "com.example.sensor_recorder.fileprovider",
                zipFile
            )
        } catch (e: Exception) {
            Timber.e(e, "Error getting recording URI")
            null
        }
    }

    /**
     * Clean up old ZIP files from cache directory
     */
    fun cleanupOldZips(context: Context) {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".zip")) {
                    val deleted = file.delete()
                    Timber.d("Deleted old ZIP: ${file.name}, success: $deleted")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up old ZIPs")
        }
    }
}
