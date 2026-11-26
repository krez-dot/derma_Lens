package com.dermalens.app.ui.screens

import android.content.Context
import com.dermalens.app.data.db.DermaDatabase
import com.dermalens.app.data.model.ScanRecord
import kotlin.math.roundToInt

/**
 * Learns each user's own trigger -> symptom patterns from their saved check-ins. Runs entirely on
 * the phone from the local database: nothing is uploaded and no internet is needed.
 *
 * How it "learns": for every trigger the user has ticked, compare their average itch (and pain)
 * on scans where they noted it against scans where they didn't. A trigger becomes a pattern once
 * it was noted on at least [MIN_TIMES_NOTED] scans, there is at least one scan without it to
 * compare against, and the difference is at least [MIN_DIFFERENCE] on the 0-3 scale (half a step,
 * e.g. between Mild and Moderate). Every new check-in updates it. It finds associations in the
 * user's own reports, not causes, and the app words it that way everywhere.
 *
 * Used by: Home (a personal tip), the pre-scan check-in (the user's usual triggers listed first),
 * the result screen (this scan matches a known pattern), and Insights.
 */
object PersonalPatterns {
    const val MIN_TIMES_NOTED = 2
    const val MIN_DIFFERENCE = 0.5

    data class Pattern(
        val trigger: String,
        val measure: String,      // "Itch" or "Pain"
        val avgWith: Double,
        val avgWithout: Double,
        val timesNoted: Int
    ) {
        val difference get() = avgWith - avgWithout

        /** Insights wording. */
        fun sentence(): String =
            "$measure was higher on scans where you noted \"$trigger\" (about ${CheckInOptions.levelLabel(avgWith.roundToInt())}, vs ${CheckInOptions.levelLabel(avgWithout.roundToInt())} otherwise)."

        /** Home / result-screen wording. */
        fun tip(): String =
            "Your ${measure.lowercase()} tends to be higher when you note \"$trigger\" ($timesNoted scans so far). ${careHint(trigger)}"
    }

    data class Learned(
        val patterns: List<Pattern>,          // strongest first
        val triggerCounts: Map<String, Int>   // how often each trigger was ticked
    ) {
        /** The user's usual triggers (ticked on 2+ scans), most frequent first. */
        val usualTriggers: List<String>
            get() = triggerCounts.filter { it.key != CheckInOptions.NOT_SURE && it.value >= MIN_TIMES_NOTED }
                .entries.sortedByDescending { it.value }.map { it.key }

        fun matching(triggers: List<String>): List<Pattern> =
            patterns.filter { it.trigger in triggers }.distinctBy { it.trigger }
    }

    val EMPTY = Learned(emptyList(), emptyMap())

    fun learn(scans: List<ScanRecord>, triggersByScan: Map<Int, List<String>>): Learned {
        val counts = triggersByScan.values.flatten().groupingBy { it }.eachCount()
        val candidates = counts.keys.filter { it != CheckInOptions.NOT_SURE }
        val measures: List<Pair<String, (ScanRecord) -> Int>> = listOf(
            "Itch" to { s: ScanRecord -> s.itchLevel },
            "Pain" to { s: ScanRecord -> s.painLevel }
        )
        val patterns = mutableListOf<Pattern>()
        for ((name, level) in measures) {
            val rated = scans.filter { level(it) >= 0 }
            for (trigger in candidates) {
                val withTrigger = rated.filter { trigger in triggersByScan[it.scanId].orEmpty() }
                val withoutTrigger = rated.filter { trigger !in triggersByScan[it.scanId].orEmpty() }
                if (withTrigger.size < MIN_TIMES_NOTED || withoutTrigger.isEmpty()) continue
                val a = withTrigger.map(level).average()
                val b = withoutTrigger.map(level).average()
                if (a - b >= MIN_DIFFERENCE) patterns += Pattern(trigger, name, a, b, withTrigger.size)
            }
        }
        return Learned(patterns.sortedByDescending { it.difference }, counts)
    }

    /** Learns from the signed-in user's saved scans. Local database only. */
    suspend fun load(context: Context): Learned {
        val prefs = context.getSharedPreferences(DermaPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val db = DermaDatabase.getDatabase(context)
        val user = db.userDao().getUserByEmail(prefs.getString(DermaPrefs.KEY_USER_EMAIL, "") ?: "") ?: return EMPTY
        val dao = db.scanRecordDao()
        return learn(dao.getScansByUserOnce(user.userId), dao.getTriggersByUser(user.userId).groupBy({ it.scanId }, { it.triggerName }))
    }

    /** A gentle, general next step per trigger. Never a diagnosis or a treatment. */
    private fun careHint(trigger: String): String = when (trigger) {
        "Heat or sweating" -> "Keeping the area cool and dry may help."
        "Sun exposure" -> "Shade and sun protection may help."
        "New soap, skincare or product" -> "Pausing the new product for a while may show if it's related."
        "Stress or lack of sleep" -> "Rest and short breaks may help."
        "Certain food" -> "A simple food diary can help you check this."
        "Pets or animals" -> "Notice whether it flares after contact with animals."
        "Close contact with someone with a rash" -> "Some rashes spread through contact, so a doctor's check is a good idea."
        else -> ""
    }.let { "$it If it keeps happening, ask a dermatologist.".trim() }
}
