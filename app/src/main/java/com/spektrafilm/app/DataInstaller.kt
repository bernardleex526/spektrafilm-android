package com.spektrafilm.app

import android.content.Context
import java.io.File

/**
 * Copies assets/spektrafilm-data to internal storage so the Rust side can
 * read it with plain file IO. Re-copies whenever the installed APK changes.
 */
object DataInstaller {
    private const val ASSET_ROOT = "spektrafilm-data"

    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, ASSET_ROOT)
        val stamp = File(target, ".installed")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = "${info.longVersionCode}:${info.lastUpdateTime}"
        if (stamp.isFile && stamp.readText() == version) return target
        target.deleteRecursively()
        copyAssetTree(context, ASSET_ROOT, target)
        stamp.writeText(version)
        return target
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetTree(context, "$assetPath/$child", File(dest, child))
    }
}
