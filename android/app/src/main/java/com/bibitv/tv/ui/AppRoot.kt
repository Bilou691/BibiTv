package com.bibitv.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibitv.tv.R
import com.bibitv.tv.data.Device
import com.bibitv.tv.data.SettingsStore
import com.bibitv.tv.data.UpdateManager
import com.bibitv.tv.player.PlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Racine de l'application : jingle, connexion, navigation et écrans superposés. */
@Composable
fun AppRoot(vm: AppViewModel) {
    val tv = Device.isTv
    // TV : interface « 10-foot », largeur logique fixée à 1280 dp quel que soit l'écran (1080p ou 4K),
    // pour des tailles identiques sur toutes les TV. Smartphone / tablette : densité normale de l'appareil.
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val widthPx = maxOf(metrics.widthPixels, metrics.heightPixels)
    val density = if (tv) androidx.compose.ui.unit.Density(density = widthPx / 1280f, fontScale = 1f)
    else androidx.compose.ui.platform.LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val dims = when {
        tv -> TvDims
        config.screenWidthDp >= 600 -> TabletDims
        else -> PhoneDims
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides density,
        LocalDims provides dims,
    ) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.Bg, surface = C.Surface, primary = Color.White)) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            LaunchedEffect(Unit) { vm.startup() }

            // Un seul écran à la fois : à la télécommande, le focus ne peut pas « fuir » vers un écran caché.
            val detail = vm.detail
            val req = vm.player
            // Smartphone / tablette : le contenu évite la barre d'état, l'encoche et la barre de navigation
            // (le lecteur, lui, occupe tout l'écran).
            val safe = if (tv || req != null) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            Box(safe) {
                when {
                    req != null -> PlayerScreen(req, vm, onClose = { vm.closePlayer() })
                    vm.updatePrompt -> UpdatePromptScreen(vm)
                    // Chargement initial (mise à jour, connexion, playlist) : logo + citations de films
                    vm.busy != null && vm.home == null -> LoadingScreen(vm.busy!!)
                    vm.client == null -> LoginScreen(vm)
                    vm.showSettings -> SettingsScreen(vm)
                    vm.showDownloads -> DownloadsScreen(vm)
                    vm.showPersonalize -> PersonalizeScreen(vm)
                    detail != null -> if (dims.compact) MobileDetailScreen(detail, vm) else DetailScreen(detail, vm)
                    tv -> MainScreen(vm)
                    else -> MobileMainScreen(vm)
                }
            }
            vm.menu?.let { m -> ActionMenu(m.title, m.actions) { vm.menu = null } }
            if (vm.home != null) vm.busy?.let { Spinner(it) }
            Toast(vm)

            if (vm.showSplash) SplashScreen { vm.showSplash = false }
        }
    }
    }
}

/** Message éphémère (téléchargement ajouté, mise à jour…). */
@Composable
private fun Toast(vm: AppViewModel) {
    val msg = vm.updateMessage ?: return
    LaunchedEffect(msg) { delay(4000); if (vm.updateMessage == msg) vm.updateMessage = null }
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(msg, color = C.Text, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF21C1C1E)).padding(horizontal = 22.dp, vertical = 14.dp))
    }
}

// =================== Jingle d'ouverture (silencieux) ===================

/**
 * Même animation que sur Windows : le décor s'allume, le logo apparaît avec un léger rebond,
 * un reflet balaie l'écran du téléviseur, puis le nom BibiTV et son reflet au sol.
 * Composition d'origine 1672×941, mise à l'échelle de l'écran.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val bg = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.86f) }
    val sweep = remember { Animatable(-1.2f) }
    val word = remember { Animatable(0f) }
    val floor = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }

    fun finish() {
        if (done) return
        done = true
        scope.launch { fade.animateTo(0f, tween(450)); onDone() }
    }

    LaunchedEffect(Unit) {
        val back = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) // léger dépassement (rebond)
        launch { bg.animateTo(1f, tween(900)) }
        launch { delay(250); logoAlpha.animateTo(1f, tween(700)) }
        launch { delay(250); logoScale.animateTo(1f, tween(1000, easing = back)) }
        launch { delay(1000); sweep.animateTo(1.2f, tween(900, easing = FastOutSlowInEasing)) }
        launch { delay(1150); word.animateTo(1f, tween(750)) }
        launch { delay(1350); floor.animateTo(1f, tween(900, easing = LinearEasing)) }
        delay(3100)
        finish()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = fade.value }.clickable { finish() },
        contentAlignment = Alignment.Center,
    ) {
        // Paysage (TV) : la composition 1672×941 remplit l'écran.
        // Portrait (smartphone) : le logo (≈ 540 px de large) occupe ~75 % de la largeur, sans déborder en hauteur.
        val scale = if (maxWidth < maxHeight) minOf(maxWidth.value * 0.75f / 540f, maxHeight.value / 941f)
        else maxOf(maxWidth.value / 1672f, maxHeight.value / 941f)
        Box(Modifier.requiredSize((1672 * scale).dp, (941 * scale).dp)) {
            Image(painterResource(R.drawable.splash_background), null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = bg.value })

            Box(
                Modifier.offset((560 * scale).dp, (160 * scale).dp).size((530 * scale).dp, (380 * scale).dp)
                    .graphicsLayer {
                        alpha = logoAlpha.value
                        scaleX = logoScale.value; scaleY = logoScale.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Reflet lumineux limité au logo (SrcAtop)
                        val s = sweep.value
                        val w = size.width; val h = size.height
                        val cx = w * (0.5f + s / 2); val cy = h * (0.5f + s / 2)
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent, 0.4f to Color.Transparent, 0.5f to Color(0xB3FFFFFF),
                                0.6f to Color.Transparent, 1f to Color.Transparent,
                                start = Offset(cx - w * 0.6f, cy - h * 0.6f), end = Offset(cx + w * 0.6f, cy + h * 0.6f),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            ) {
                Image(painterResource(R.drawable.splash_icon), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }

            Image(painterResource(R.drawable.splash_text), null, contentScale = ContentScale.FillBounds,
                // Décalage lu dans graphicsLayer : l'animation ne recompose plus l'écran à chaque image.
                modifier = Modifier.offset((560 * scale).dp, (540 * scale).dp).size((530 * scale).dp, (180 * scale).dp)
                    .graphicsLayer { alpha = word.value; translationY = 16 * (1 - word.value) * scale * density })
            Image(painterResource(R.drawable.splash_floor), null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset((560 * scale).dp, (738 * scale).dp).size((540 * scale).dp, (203 * scale).dp)
                    .graphicsLayer { alpha = floor.value })
        }
    }
}

// =================== Connexion ===================

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf(SettingsStore.serverUrl) }
    var user by remember { mutableStateOf(SettingsStore.username) }
    var pwd by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = C.Text, unfocusedTextColor = C.Text,
        focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
        focusedBorderColor = Color.White, unfocusedBorderColor = Color.Transparent,
        focusedLabelColor = C.Text2, unfocusedLabelColor = C.Text2, cursorColor = Color.White,
    )

    val brand: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Image(painterResource(R.drawable.logo), null, modifier = Modifier.size(if (Device.isTv) 140.dp else 96.dp))
            Text("BibiTV", color = C.Text, fontSize = if (Device.isTv) 52.sp else 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text("Connectez votre abonnement Xtream Codes", color = C.Text2, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Text("v${UpdateManager.currentVersion}", color = C.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
    val form: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            OutlinedTextField(server, { server = it }, label = { Text("Adresse du serveur") }, singleLine = true, colors = colors,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().focusRequester(first),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, colors = colors,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(pwd, { pwd = it }, label = { Text("Mot de passe") }, singleLine = true, colors = colors,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.login(server, user, pwd) }))
            Spacer(Modifier.height(24.dp))
            PillButton("Se connecter", { vm.login(server, user, pwd) }, Modifier.fillMaxWidth(), primary = true, fill = true)
            vm.loginError?.let { Text(it, color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)) }
            Text("Le mot de passe est chiffré et stocké uniquement sur ${Device.label}.", color = C.Text3, fontSize = 12.sp,
                modifier = Modifier.padding(top = 18.dp))
        }
    }

    // Le contenu défile et se redimensionne au-dessus du clavier visuel (mode large ou étroit) :
    // le champ en cours de saisie n'est jamais masqué.
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1A1A24), C.Bg), radius = 1400f))) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            if (maxWidth >= 900.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    brand()
                    Spacer(Modifier.width(72.dp))
                    Box(Modifier.width(460.dp)) { form() }
                }
            } else {
                brand()
                Spacer(Modifier.height(32.dp))
                form()
            }
        }
    }
    LaunchedEffect(vm.showSplash) { if (!vm.showSplash) runCatching { first.requestFocus() } }
}
