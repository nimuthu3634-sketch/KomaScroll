package komascroll.library.failover

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * When a chapter fails to open in the reader, offers to look for it in the user's other sources
 * instead of just closing. Returns false (so the reader closes as usual) when failover is off.
 */
object ReaderFailoverPrompt {

    fun show(activity: Activity, mangaId: Long?, chapterId: Long?, error: Throwable): Boolean {
        if (mangaId == null || !Injekt.get<LabPreferences>().sourceFailoverEnabled().get()) return false

        val reason = error.message?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.stringResource(KSR.strings.failover_reader_title))
            .setMessage(
                listOfNotNull(reason, activity.stringResource(KSR.strings.failover_reader_message)).joinToString("\n\n"),
            )
            .setPositiveButton(activity.stringResource(KSR.strings.failover_button)) { _, _ ->
                activity.startActivity(SourceFailoverScreen.intent(activity, mangaId, chapterId))
            }
            .setNegativeButton(activity.stringResource(MR.strings.action_cancel), null)
            .setOnDismissListener { activity.finish() }
            .show()
        return true
    }
}
