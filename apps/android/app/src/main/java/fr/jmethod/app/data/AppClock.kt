package fr.jmethod.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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

/** Date du jour, recalculée toutes les 30 s tant qu’elle est observée (changement de jour, comme le web). */
fun AppClock.todayTicks(): Flow<LocalDate> = flow {
    while (true) {
        emit(today())
        delay(30_000)
    }
}.distinctUntilChanged()
