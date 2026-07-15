package app.netwatch

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {

    private const val FILE_NAME = "netwatch-log.txt"
    private const val MAX_BYTES = 512 * 1024L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun timestamp(): String =
        SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())

    @Synchronized
    fun append(ctx: Context, line: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                val kept = f.readLines().takeLast(1000)
                f.writeText(kept.joinToString("\n") + "\n")
            }
            f.appendText("${timestamp()}  $line\n")
        } catch (_: Exception) {
        }
    }

    fun readTailNewestFirst(ctx: Context, maxLines: Int = 300): String {
        val f = file(ctx)
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(maxLines).reversed().joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Exception) {
        }
    }
}
