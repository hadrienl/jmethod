package fr.jmethod.app.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Les animations sont désactivées dans les réglages d’accessibilité (« Supprimer les animations »). */
fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

private class Particle(
    var x: Float, var y: Float, var angle: Float, var velocity: Float,
    val color: Color, val decay: Float, val gravity: Float, val ticks: Int,
    var tick: Int = 0, var tilt: Float, val wobbleSpeed: Float,
)

internal data class Burst(
    val originX: Float, val originY: Float, val count: Int, val spreadDeg: Float, val angleDeg: Float,
    val startVelocity: Float, val gravity: Float, val ticks: Int, val colors: List<Color>,
)

@Stable
class CelebrationState {
    internal val requests = MutableSharedFlow<List<Pair<Long, Burst>>>(extraBufferCapacity = 4)

    /** Feu d’artifice de fin de soirée (`CompletionFireworks`) : cinq gerbes espacées de 400 ms. */
    fun fireworks() {
        val colors = listOf(0xFF7C3AED, 0xFFFBBF24, 0xFF38BDF8, 0xFF34D399, 0xFFFB7185).map { Color(it) }
        val origins = listOf(0.25f to 0.3f, 0.75f to 0.4f, 0.5f to 0.2f, 0.2f to 0.5f, 0.8f to 0.25f)
        requests.tryEmit(origins.mapIndexed { index, (x, y) -> index * 400L to Burst(x, y, 75, 360f, 90f, 32f, 0.65f, 140, colors) })
    }

    /** Petite gerbe après une autoévaluation de 75 ou plus (`ScoreModal`). */
    fun confetti() {
        val colors = listOf(0xFF38BDF8, 0xFF10B981, 0xFFF59E0B, 0xFF8B5CF6).map { Color(it) }
        requests.tryEmit(listOf(0L to Burst(0.5f, 0.7f, 50, 60f, 90f, 45f, 1f, 200, colors)))
    }
}

/**
 * Canvas plein écran, transparent aux touchers et ignoré par TalkBack. Physique reprise de
 * canvas-confetti (vitesse amortie, gravité, fondu). Rien n’est animé si l’utilisateur a
 * désactivé les animations du système.
 */
@Composable
fun CelebrationOverlay(state: CelebrationState, modifier: Modifier = Modifier) {
    val particles = remember { mutableListOf<Particle>() }
    var frame by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(Size.Zero) }

    var spawned by remember { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        state.requests.collect { bursts ->
            if (!animationsEnabled()) return@collect
            for ((wait, burst) in bursts) {
                launch {
                    delay(wait)
                    val random = Random.Default
                    repeat(burst.count) {
                        val radAngle = burst.angleDeg * (PI / 180).toFloat()
                        val radSpread = burst.spreadDeg * (PI / 180).toFloat()
                        particles += Particle(
                            x = burst.originX * size.width,
                            y = burst.originY * size.height,
                            angle = -radAngle + (0.5f * radSpread - random.nextFloat() * radSpread),
                            velocity = (burst.startVelocity * 0.5f + random.nextFloat() * burst.startVelocity) * density / 2.5f,
                            color = burst.colors[random.nextInt(burst.colors.size)],
                            decay = 0.9f,
                            gravity = burst.gravity * 3f * density / 2.5f,
                            ticks = burst.ticks,
                            tilt = random.nextFloat() * PI.toFloat(),
                            wobbleSpeed = 0.05f + random.nextFloat() * 0.06f,
                        )
                    }
                    spawned++
                }
            }
        }
    }

    // Une seule boucle d’animation à la fois : elle redémarre à chaque nouvelle gerbe.
    LaunchedEffect(spawned) {
        while (particles.isNotEmpty()) {
            withFrameNanos { time ->
                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.x += cos(p.angle) * p.velocity
                    p.y += sin(p.angle) * p.velocity + p.gravity
                    p.velocity *= p.decay
                    p.tilt += p.wobbleSpeed * 4f
                    p.tick++
                    if (p.tick >= p.ticks) iterator.remove()
                }
                frame = time
            }
        }
        frame = 0L
    }

    Canvas(modifier.fillMaxSize().onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }.clearAndSetSemantics {}) {
        frame // relit l’état pour redessiner à chaque image
        val side = 8f * density / 1.5f
        for (p in particles) {
            val alpha = (1f - p.tick.toFloat() / p.ticks).coerceIn(0f, 1f)
            rotate(degrees = p.tilt * 57.3f, pivot = Offset(p.x, p.y)) {
                drawRect(p.color.copy(alpha = alpha), topLeft = Offset(p.x - side / 2, p.y - side / 4), size = Size(side, side / 2))
            }
        }
    }
}
