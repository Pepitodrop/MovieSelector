package com.luisbenedikt.movieselector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luisbenedikt.movieselector.data.MovieDto
import com.luisbenedikt.movieselector.data.MovieSelectorApi
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val Background = Color(0xFF0B0E14)
private val CardBg = Color(0xFF14161F)
private val Accent = Color(0xFFFF4F70)
private val TextLight = Color(0xFFF4F1E8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = MovieSelectorApi(BuildConfig.API_BASE_URL)
        setContent {
            val state = remember { GameState(api) }
            MaterialTheme(colorScheme = darkColorScheme(background = Background, primary = Accent)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    MovieSelectorApp(state)
                }
            }
        }
    }
}

@Composable
fun MovieSelectorApp(state: GameState) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = true) { state.handleBack() }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        when (state.screen) {
            Screen.FILTERS -> FiltersScreen(state, onStart = { state.startGame(scope) })
            Screen.LOADING -> LoadingScreen()
            Screen.CARD -> CardScreen(state, scope)
            Screen.REJECT_ALL -> RejectAllScreen(
                onReshuffle = { state.reshuffleAll(scope) },
                onBringBack = { state.bringBackLast5(scope) },
            )
            Screen.RESULT -> ResultScreen(state.resultMovie, onRestart = { state.restart() })
            Screen.ERROR -> ErrorScreen(state.errorMessage, onRetry = { state.restart() })
        }
    }
}

@Composable
private fun FiltersScreen(state: GameState, onStart: () -> Unit) {
    val checked = remember { mutableStateMapOf(*ALL_PROVIDERS.map { it.first to (it.first in state.selectedProviders) }.toTypedArray()) }
    var runtime by remember { mutableStateOf(state.runtime) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🍿 Movie Selector", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextLight)
        Spacer(Modifier.height(8.dp))
        Text("Swipe through your Wencke watchlist until something sticks.", color = TextLight.copy(alpha = 0.8f))
        Spacer(Modifier.height(24.dp))
        SectionLabel("Providers")
        FlowChips(ALL_PROVIDERS) { code, label ->
            FilterChip(
                selected = checked[code] == true,
                onClick = { checked[code] = !(checked[code] ?: false) },
                label = { Text(label) },
            )
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("Runtime")
        FlowChips(RUNTIME_OPTIONS) { value, label ->
            FilterChip(selected = runtime == value, onClick = { runtime = value }, label = { Text(label) })
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                state.selectedProviders = checked.filterValues { it }.keys.toMutableSet()
                state.runtime = runtime
                onStart()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = checked.values.any { it },
        ) { Text("Start", fontSize = 18.sp) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = TextLight.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun <T> FlowChips(items: List<Pair<T, String>>, chip: @Composable (T, String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label) -> chip(value, label) }
    }
}

@Composable
private fun LoadingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(16.dp))
        Text("Loading your watchlist…", color = TextLight)
    }
}

private const val SWIPE_THRESHOLD_DP = 120f

@Composable
private fun CardScreen(state: GameState, scope: kotlinx.coroutines.CoroutineScope) {
    val movie = state.currentMovie
    if (movie == null) {
        RejectAllScreen(onReshuffle = { state.reshuffleAll(scope) }, onBringBack = { state.bringBackLast5(scope) })
        return
    }
    val haptic = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${state.remaining} left", color = TextLight.copy(alpha = 0.7f))
            TextButton(onClick = { state.undo(scope) }) { Text("Undo", color = TextLight) }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .rotate(offsetX.value / 40f)
                .background(CardBg, RoundedCornerShape(20.dp))
                .semantics { contentDescription = "${movie.title} movie card. Drag left to reject, right to select." }
                .pointerInput(movie.id) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -thresholdPx -> { state.reject(scope); offsetX.snapTo(0f) }
                                    offsetX.value >= thresholdPx -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        state.accept(scope)
                                        offsetX.snapTo(0f)
                                    }
                                    else -> offsetX.animateTo(0f)
                                }
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x) }
                    }
                },
        ) {
            MovieCardContent(movie)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            FloatingActionButton(
                onClick = { state.reject(scope) },
                containerColor = Color(0xFF2A1420),
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Reject this movie" },
            ) { Text("✕", fontSize = 26.sp, color = TextLight) }
            FloatingActionButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); state.accept(scope) },
                containerColor = Color(0xFF14301F),
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Select this movie" },
            ) { Text("🍿", fontSize = 26.sp) }
        }
    }
}

@Composable
private fun MovieCardContent(movie: MovieDto) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A2030)), contentAlignment = Alignment.Center) {
            Text("🎬", fontSize = 64.sp)
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(movie.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextLight)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                movie.year?.let { Text(it.toString(), color = TextLight.copy(alpha = 0.8f)) }
                movie.runtimeMinutes?.let { Text("$it min", color = TextLight.copy(alpha = 0.8f)) }
                movie.rating?.let { Text("★ $it", color = TextLight.copy(alpha = 0.8f)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                movie.providers.forEach { code ->
                    val label = ALL_PROVIDERS.firstOrNull { it.first == code }?.second ?: code
                    AssistChip(onClick = {}, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
    }
}

@Composable
private fun RejectAllScreen(onReshuffle: () -> Unit, onBringBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("YOU REJECTED EVERYTHING 😅", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextLight, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onReshuffle, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Reshuffle all movies") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBringBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Bring back last 5") }
    }
}

@Composable
private fun ResultScreen(movie: MovieDto?, onRestart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🍿 MOVIE TIME!", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextLight)
        Spacer(Modifier.height(20.dp))
        if (movie != null) {
            Box(Modifier.fillMaxWidth(0.8f).aspectRatio(0.68f).background(CardBg, RoundedCornerShape(20.dp))) {
                MovieCardContent(movie)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Play again") }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Something went wrong", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextLight)
        Spacer(Modifier.height(12.dp))
        Text(message, color = TextLight.copy(alpha = 0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
