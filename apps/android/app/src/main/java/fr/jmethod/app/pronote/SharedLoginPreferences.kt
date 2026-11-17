package fr.jmethod.app.pronote

import android.content.Context

/** Établissement et type de compte, en stockage privé (sauvegardes désactivées). Jamais le mot de passe. */
class SharedLoginPreferences(context: Context) : LoginPreferences {
    private val prefs = context.applicationContext.getSharedPreferences("login", Context.MODE_PRIVATE)

    override fun load(): Pair<String, AccountType>? {
        val school = prefs.getString(SCHOOL, null)?.takeIf { it.isNotBlank() } ?: return null
        val type = runCatching { AccountType.valueOf(prefs.getString(TYPE, null).orEmpty()) }.getOrDefault(AccountType.STUDENT)
        return school to type
    }

    override fun save(school: String, accountType: AccountType) {
        prefs.edit().putString(SCHOOL, school).putString(TYPE, accountType.name).apply()
    }

    private companion object {
        const val SCHOOL = "school"
        const val TYPE = "accountType"
    }
}
