package fr.jmethod.app.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Jours calendaires au fuseau Europe/Paris, au format `YYYY-MM-DD` comme le web. */
fun interface AppClock {
    fun now(): Instant

    fun today(): LocalDate = now().atZone(ZONE).toLocalDate()

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Paris")
        val SYSTEM = AppClock { Clock.systemUTC().instant() }
    }
}
