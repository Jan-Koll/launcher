package de.jrpie.android.launcher.apps

import android.content.Context
import android.icu.text.Normalizer2
import android.os.Build
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.AppAction
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.actions.ShortcutAction
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.util.countOccurrences
import de.jrpie.android.launcher.util.isSubsequent
import java.util.Locale
import kotlin.text.Regex.Companion.escape

class AppFilter(
    var context: Context,
    var query: String,
    var favoritesVisibility: AppSetVisibility = AppSetVisibility.VISIBLE,
    var hiddenVisibility: AppSetVisibility = AppSetVisibility.HIDDEN,
    var privateSpaceVisibility: AppSetVisibility = AppSetVisibility.VISIBLE
) {

    operator fun invoke(apps: List<AbstractDetailedAppInfo>): List<AbstractDetailedAppInfo> {
        var apps =
            apps.sortedBy { app -> app.getCustomLabel(context).lowercase(Locale.ROOT) }
        val hidden = LauncherPreferences.apps().hidden() ?: setOf()
        val favorites = LauncherPreferences.apps().favorites() ?: setOf()
        val private = apps.filter { it.isPrivate() }
            .map { it.getRawInfo() }.toSet()

        apps = apps.filter { info ->
            favoritesVisibility.predicate(favorites, info)
                    && hiddenVisibility.predicate(hidden, info)
                    && privateSpaceVisibility.predicate(private, info)
        }

        if (LauncherPreferences.apps().hideBoundApps()) {
            val boundApps = Gesture.entries
                .filter(Gesture::isEnabled)
                .mapNotNull { g -> Action.forGesture(g) }
                .mapNotNull {
                    (it as? AppAction)?.app
                    ?: (it as? ShortcutAction)?.shortcut
                }
                .toSet()
            apps = apps.filterNot { info -> boundApps.contains(info.getRawInfo()) }
        }

        // normalize text for search
        val allowedSpecialCharacters = unicodeNormalize(query)
            .lowercase(Locale.ROOT)
            .toCharArray()
            .distinct()
            .filter { c -> !c.isLetter() }
            .map { c -> escape(c.toString()) }
            .fold("") { x, y -> x + y }
        val disallowedCharsRegex = "[^\\p{L}$allowedSpecialCharacters]".toRegex()

        fun normalize(text: String): String {
            return unicodeNormalize(text).replace(disallowedCharsRegex, "")
        }

        if (query.isEmpty()) {
            return apps
        }
        val r: MutableSet<AbstractDetailedAppInfo> = hashSetOf()
        val normalizedQuery: String = normalize(query)
        val subsequentResult: MutableList<AbstractDetailedAppInfo> = mutableListOf()
        val occurrences: MutableMap<AbstractDetailedAppInfo, Int> = mutableMapOf()
        for (item in apps) {
            val itemLabel: String = normalize(item.getCustomLabel(context))

            if (itemLabel.startsWith(normalizedQuery)) {
                r.add(item)
            } else if (itemLabel.contains(normalizedQuery)) {
                r.add(item)
            }
            if (LauncherPreferences.functionality().searchFuzzy()) {
                if (isSubsequent(itemLabel, normalizedQuery)) {
                    subsequentResult.add(item)
                }
                occurrences[item] = countOccurrences(itemLabel, normalizedQuery)
            }
        }
        if (LauncherPreferences.functionality().searchFuzzy() && r.size != 1) {
            if (subsequentResult.isNotEmpty()) {
                r.addAll(subsequentResult)
            } else {
                val maxOccurrences = occurrences.values.maxOrNull()
                if (maxOccurrences == 0) return apps
                val result = occurrences.filter { it.value == maxOccurrences }
                r.addAll(result.keys)
            }
        }
        return r.toList().sortedBy { it.getCustomLabel(context).lowercase(Locale.ROOT) }
    }


    companion object {
        enum class AppSetVisibility(
            val predicate: (set: Set<AbstractAppInfo>, AbstractDetailedAppInfo) -> Boolean
        ) {
            VISIBLE({ _, _ -> true }),
            HIDDEN({ set, appInfo -> !set.contains(appInfo.getRawInfo()) }),
            EXCLUSIVE({ set, appInfo -> set.contains(appInfo.getRawInfo()) }),
            ;
        }

        private fun unicodeNormalize(s: String): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val normalizer = Normalizer2.getNFKDInstance()
                return normalizer.normalize(s.lowercase(Locale.ROOT))
            }
            return s.lowercase(Locale.ROOT)
        }
    }
}