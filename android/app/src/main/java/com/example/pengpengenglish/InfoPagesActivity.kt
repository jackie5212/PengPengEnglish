package com.example.pengpengenglish

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

abstract class BaseInfoPageActivity : ComponentActivity() {
    abstract val layoutResId: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutResId)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }
}

class ProfileActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_profile
}

class FavoritesActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_favorites
}

class GeneralSettingsActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_setting_general
}

class PlaybackSettingsActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_setting_playback
}

class PrivacySettingsActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_setting_privacy
}

class VersionInfoActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_version_info

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contentView = findViewById<TextView>(R.id.tvPageContent)
        val checkView = findViewById<TextView>(R.id.btnCheckUpdate)
        val localVersion = getLocalVersionName()
        contentView.text = "当前版本：$localVersion\n线上版本：检查中..."

        checkView.setOnClickListener {
            checkUpdate(contentView, showLatestToast = true)
        }

        checkUpdate(contentView, showLatestToast = false)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun checkUpdate(contentView: TextView, showLatestToast: Boolean) {
        val localVersion = getLocalVersionName()
        contentView.text = "当前版本：$localVersion\n线上版本：检查中..."
        ioExecutor.execute {
            val remoteVersion = fetchRemoteVersion()
            runOnUiThread {
                if (remoteVersion.isNullOrBlank()) {
                    contentView.text = "当前版本：$localVersion\n线上版本：读取失败"
                    return@runOnUiThread
                }
                contentView.text = "当前版本：$localVersion\n线上版本：$remoteVersion"
                if (isRemoteNewer(localVersion, remoteVersion)) {
                    showUpdateDialog(remoteVersion)
                } else if (showLatestToast) {
                    contentView.text = "当前版本：$localVersion\n线上版本：$remoteVersion\n已是最新版本"
                }
            }
        }
    }

    private fun showUpdateDialog(remoteVersion: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage("检测到新版本 $remoteVersion，是否前往更新？")
            .setNegativeButton("稍后") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("去更新") { dialog, _ ->
                dialog.dismiss()
                openAppStoreByInstaller()
            }
            .show()
    }

    private fun getLocalVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "0"
        } catch (_: Throwable) {
            "0"
        }
    }

    private fun fetchRemoteVersion(): String? {
        return try {
            val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("ReleaseVersion:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isRemoteNewer(localVersion: String, remoteVersion: String): Boolean {
        return compareVersion(remoteVersion, localVersion) > 0
    }

    private fun compareVersion(a: String, b: String): Int {
        fun parse(v: String): List<Int> {
            return v.trim()
                .split('.', '-', '_')
                .map { token -> token.toIntOrNull() ?: 0 }
        }
        val x = parse(a)
        val y = parse(b)
        val size = maxOf(x.size, y.size)
        for (i in 0 until size) {
            val xv = x.getOrElse(i) { 0 }
            val yv = y.getOrElse(i) { 0 }
            if (xv != yv) return xv.compareTo(yv)
        }
        return 0
    }

    private fun openAppStoreByInstaller() {
        val installer = getInstallerPackageNameCompat()
        val targetStorePackage = when (installer) {
            "com.tencent.android.qqdownloader" -> "com.tencent.android.qqdownloader"
            "com.bbk.appstore" -> "com.bbk.appstore"
            else -> null
        }

        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!targetStorePackage.isNullOrBlank()) {
                setPackage(targetStorePackage)
            }
        }

        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://a.app.qq.com/o/simple.jsp?pkgname=$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(fallbackIntent)
        }
    }

    private fun getInstallerPackageNameCompat(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val VERSION_URL = "http://47.97.36.224/tts/version.txt"
    }
}

class UserAgreementActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_user_agreement
}

class PrivacyPolicyActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_privacy_policy
}

class AcknowledgementsActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_acknowledgements
}

class DeveloperInfoActivity : BaseInfoPageActivity() {
    override val layoutResId: Int = R.layout.activity_developer_info
}

class PhoneInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_info)

        findViewById<TextView>(R.id.tvPageContent).text = buildPhoneInfoText()
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun buildPhoneInfoText(): String {
        return buildString {
            appendLine("品牌：${Build.BRAND}")
            appendLine("制造商：${Build.MANUFACTURER}")
            appendLine("设备：${Build.DEVICE}")
            appendLine("型号：${Build.MODEL}")
            appendLine("产品名：${Build.PRODUCT}")
            appendLine("硬件：${Build.HARDWARE}")
            appendLine("主板：${Build.BOARD}")
            appendLine("指纹：${Build.FINGERPRINT}")
            appendLine("Android 版本：${Build.VERSION.RELEASE}")
            appendLine("SDK/API：${Build.VERSION.SDK_INT}")
            appendLine("构建 ID：${Build.ID}")
            appendLine("构建类型：${Build.TYPE}")
        }.trim()
    }
}
