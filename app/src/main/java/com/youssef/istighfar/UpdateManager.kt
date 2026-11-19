package com.youssef.istighfar

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/f0uri/istighfar/main/version.json"
    private const val APK_URL =
        "https://github.com/f0uri/istighfar/releases/latest/download/app-debug.apk"

    fun checkForUpdate(activity: Activity) {
        Thread {
            try {
                val url = URL(VERSION_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Istighfar-App")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", APK_URL)
                @Suppress("DEPRECATION")
                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION") info.versionCode
                }
                if (remoteCode > currentCode) {
                    Handler(Looper.getMainLooper()).post {
                        showUpdateDialog(activity, remoteName, apkUrl)
                    }
                }
            } catch (e: Exception) {
                // silent: offline or no update info — no dialog
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, remoteName: String, apkUrl: String) {
        if (activity.isFinishing) return
        val dialog = Dialog(activity)
        var gone = false
        dialog.setOnDismissListener { gone = true }
        dialog.setContentView(R.layout.dialog_update)
        dialog.setCancelable(true)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.35f)
            val params = w.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            w.attributes = params
        }
        val versionText = dialog.findViewById<TextView>(R.id.updateVersion)
        versionText.text = activity.getString(R.string.update_version, remoteName)
        val progress = dialog.findViewById<ProgressBar>(R.id.updateProgress)
        val btnUpdate = dialog.findViewById<Button>(R.id.btnUpdate)
        val btnLater = dialog.findViewById<Button>(R.id.btnLater)
        btnLater.setOnClickListener { dialog.dismiss() }
        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            btnLater.isEnabled = false
            btnUpdate.text = activity.getString(R.string.update_downloading)
            progress.visibility = View.VISIBLE
            downloadApk(activity, apkUrl, progress) { file ->
                activity.runOnUiThread {
                    val wasGone = gone
                    dialog.dismiss()
                    if (file == null) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_download_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (!wasGone) {
                        installApk(activity, file)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun downloadApk(
        activity: Activity,
        apkUrl: String,
        progress: ProgressBar,
        done: (File?) -> Unit
    ) {
        Thread {
            var out: FileOutputStream? = null
            try {
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Istighfar-App")
                val total = conn.contentLength
                val dir = File(activity.getExternalFilesDir(null), "updates")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "istighfar-update.apk")
                if (file.exists()) file.delete()
                val input = conn.inputStream
                out = FileOutputStream(file)
                val buf = ByteArray(8192)
                var read: Int
                var downloaded = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        activity.runOnUiThread { progress.progress = pct }
                    }
                }
                out.flush()
                done(file)
            } catch (e: Exception) {
                done(null)
            } finally {
                try {
                    out?.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // system installer handles the unknown-apps permission prompt
        }
    }
}
