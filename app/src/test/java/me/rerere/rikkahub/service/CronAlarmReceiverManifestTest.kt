package me.rerere.rikkahub.service

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards against a regression where the explicit PendingIntent targets for scheduled jobs
 * are dropped from AndroidManifest.xml. Lost in the 2.4.5 merge: both alarm receivers were
 * undeclared, so AlarmManager broadcasts went nowhere and cron jobs silently never fired in
 * the background (they only ran when the cron UI opened and the WorkManager catchup path
 * reconciled).
 */
class CronAlarmReceiverManifestTest {
    @Test
    fun cronAlarmReceiversAreDeclared() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val receiverNames = document
            .getElementsByTagName("receiver")
            .asElements()
            .mapNotNull { it.androidName }
            .toSet()

        assertTrue(
            "ExactCronAlarmReceiver (LLM-mode alarm target) must be declared in the manifest",
            receiverNames.contains(".service.ExactCronAlarmReceiver"),
        )
        assertTrue(
            "DirectCronAlarmReceiver (direct-mode alarm target) must be declared in the manifest",
            receiverNames.contains(".service.DirectCronAlarmReceiver"),
        )
    }

    @Test
    fun cronBootReceiverHandlesReschedulingActions() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val bootReceiver = document
            .getElementsByTagName("receiver")
            .asElements()
            .find { it.androidName == ".service.CronBootReceiver" }

        assertTrue("CronBootReceiver must be declared in the manifest", bootReceiver != null)
        bootReceiver?.let {
            // Lost in the 2.4.5 merge: time-zone changes and exact-alarm permission grants
            // must re-trigger scheduling reconciliation.
            assertTrue(
                "CronBootReceiver must handle TIMEZONE_CHANGED to re-schedule after a zone switch",
                it.hasAction("android.intent.action.TIMEZONE_CHANGED"),
            )
            assertTrue(
                "CronBootReceiver must handle SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED " +
                    "so jobs auto-promote when the exact-alarm permission is granted",
                it.hasAction("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
            )
        }
    }

    private fun Element.hasAction(name: String): Boolean =
        getElementsByTagName("action")
            .asElements()
            .any { it.androidName == name }

    private val Element.androidName: String?
        get() = getAttribute("android:name").takeIf { it.isNotBlank() }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
