package lt.nook.app.data

import android.content.SharedPreferences

/** The three Todoist project names (changeable in Settings). */
class Settings(private val prefs: SharedPreferences) {
    fun projectNames(): Map<Kind, String> = Kind.entries.associateWith { prefs.getString("project_${it.name}", null) ?: DEFAULTS.getValue(it) }
    fun save(names: Map<Kind, String>) {
        val e = prefs.edit()
        names.forEach { (k, v) -> e.putString("project_${k.name}", v.trim()) }
        e.apply()
    }

    companion object {
        val DEFAULTS = mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks",
            Kind.ACHIEVEMENTS to "Achievements")
    }
}
