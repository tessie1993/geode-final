package dev.geode.feature.diagnostics.handlers

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.geode.feature.diagnostics.core.AppFacts
import dev.geode.feature.diagnostics.core.DeviceFacts
import dev.geode.feature.diagnostics.core.DiagnosticsReport
import dev.geode.feature.diagnostics.core.ReportFile
import dev.geode.feature.diagnostics.core.SystemFacts
import java.io.File
import java.io.IOException

// The real ports over Android, which :app wires at the switch-over (ADR-0003).

/** The cache directory the diagnostics file is written to; res/xml/diagnostics_paths.xml shares it alone. */
const val REPORT_DIRECTORY = "diagnostics"

/** Appended to the app's package name for the provider's authority, as in the module's manifest. */
const val AUTHORITY_SUFFIX = ".diagnostics"

private const val PARTIAL_SUFFIX = ".partial"

/** No optional package information is asked for: the version is all the report needs. */
private const val NO_PACKAGE_FLAGS = 0

/** The provider the diagnostics file is shared through (see the module's manifest). */
class DiagnosticsFileProvider : FileProvider()

/**
 * Writes the diagnostics file into [directory], replacing the last one, and returns it as [grant]
 * makes it shareable. Written beside the target and renamed over it, so a half-written file is never
 * shared.
 */
class CacheReportFiles(
    private val directory: File,
    private val grant: (File) -> String,
) : ReportFiles {
    override fun write(text: String): ReportFile {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $REPORT_DIRECTORY")
        val target = File(directory, DiagnosticsReport.FILE_NAME)
        val partial = File(directory, DiagnosticsReport.FILE_NAME + PARTIAL_SUFFIX)
        partial.writeText(text)
        if (!partial.renameTo(target)) throw IOException("cannot replace ${DiagnosticsReport.FILE_NAME}")
        return ReportFile(grant(target))
    }
}

/** The diagnostics file in the app's cache, shared through [DiagnosticsFileProvider]. */
fun androidReportFiles(context: Context): ReportFiles =
    CacheReportFiles(File(context.cacheDir, REPORT_DIRECTORY)) { file ->
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file).toString()
    }

/** The system share sheet, from the app's [context]. */
class ChooserShareSheet(
    private val context: Context,
) : ShareSheet {
    override fun open(file: ReportFile): Boolean {
        val send = shareIntent(Uri.parse(file.uri), context.getString(R.string.diagnostics_share_subject))
        val chooser =
            Intent
                .createChooser(send, context.getString(R.string.diagnostics_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // No activity to take it (ActivityNotFoundException), or one that refused: nothing could receive it.
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}

/** What the share sheet is handed: the file to read, with read access granted to the app picked. */
fun shareIntent(
    uri: Uri,
    subject: String,
): Intent =
    Intent(Intent.ACTION_SEND)
        .setType(DiagnosticsReport.MIME_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = ClipData.newRawUri(subject, uri) }

/** The app's version and the phone's maker, model and Android version: nothing that identifies anyone. */
class AndroidSystemFacts(
    private val context: Context,
) : SystemFacts {
    override fun app(): AppFacts {
        val info = packageInfo()
        return AppFacts(versionName = info.versionName.orEmpty(), versionCode = info.longVersionCode)
    }

    override fun device(): DeviceFacts =
        DeviceFacts(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
        )

    private fun packageInfo(): PackageInfo {
        val packages = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(NO_PACKAGE_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(context.packageName, NO_PACKAGE_FLAGS)
        }
    }
}
