package com.bibitv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibitv.tv.R
import com.bibitv.tv.data.UpdateManager
import kotlinx.coroutines.delay

/** Répliques cultes affichées pendant le chargement de la playlist. */
private val quotes = listOf(
    "Que la Force soit avec toi." to "Star Wars",
    "Je suis ton père." to "L'Empire contre-attaque",
    "Je reviendrai." to "Terminator",
    "Hasta la vista, baby." to "Terminator 2",
    "Houston, nous avons un problème." to "Apollo 13",
    "Pourquoi tant de sérieux ?" to "The Dark Knight",
    "Vers l'infini et au-delà !" to "Toy Story",
    "La vie, c'est comme une boîte de chocolats : on ne sait jamais sur quoi on va tomber." to "Forrest Gump",
    "Je vais lui faire une offre qu'il ne pourra pas refuser." to "Le Parrain",
    "E.T. téléphone maison." to "E.T., l'extra-terrestre",
    "Je suis le roi du monde !" to "Titanic",
    "Il n'y a pas de cuillère." to "Matrix",
    "Mon précieux…" to "Le Seigneur des anneaux",
    "Jusqu'ici tout va bien… L'important, c'est pas la chute, c'est l'atterrissage." to "La Haine",
    "Les cons, ça ose tout. C'est même à ça qu'on les reconnaît." to "Les Tontons flingueurs",
    "Atmosphère, atmosphère ! Est-ce que j'ai une gueule d'atmosphère ?" to "Hôtel du Nord",
    "T'as d'beaux yeux, tu sais." to "Quai des brumes",
    "Le monde se divise en deux catégories : ceux qui ont un pistolet chargé, et ceux qui creusent. Toi, tu creuses." to "Le Bon, la Brute et le Truand",
    "Je s'appelle Groot." to "Les Gardiens de la Galaxie",
    "Wakanda forever !" to "Black Panther",
    "Say hello to my little friend!" to "Scarface",
    "L'hiver vient." to "Game of Thrones",
    "Dis mon nom." to "Breaking Bad",
    "Un grand pouvoir implique de grandes responsabilités." to "Spider-Man",
)

/** Écran de chargement : décor et logo BibiTV, état du chargement et répliques cultes. */
@Composable
fun LoadingScreen(status: String) {
    var index by remember { mutableIntStateOf(quotes.indices.random()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            index = (index + 1 + (0 until quotes.size - 1).random()) % quotes.size
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.splash_background), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 40.dp)) {
            Image(painterResource(R.drawable.splash_icon), null, modifier = Modifier.width(300.dp).height(215.dp))
            Image(painterResource(R.drawable.splash_text), null, modifier = Modifier.width(300.dp).height(102.dp))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Chargement en cours…", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            Text(status, color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(34.dp))
            AnimatedContent(
                targetState = index,
                transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
                label = "quote",
            ) { i ->
                val (quote, film) = quotes[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(760.dp)) {
                    Text("« $quote »", color = Color(0xFFE5E5EA), fontSize = 20.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                    Text("— $film", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Mise à jour proposée au démarrage, avant le chargement de la playlist. */
@Composable
fun UpdatePromptScreen(vm: AppViewModel) {
    val u = vm.update ?: return
    val first = remember { FocusRequester() }
    BackHandler { if (vm.updateProgress == null) vm.skipUpdate() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.splash_background), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(620.dp)) {
            Image(painterResource(R.drawable.splash_icon), null, modifier = Modifier.width(240.dp).height(172.dp))
            Text("Une mise à jour est disponible", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("BibiTV ${u.version}  ·  vous avez la version ${UpdateManager.currentVersion}", color = C.Text2, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp))
            val p = vm.updateProgress
            if (p != null) {
                Text("Téléchargement… ${(p * 100).toInt()} %", color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 28.dp))
                ProgressLine(p, Modifier.fillMaxWidth().padding(top = 12.dp))
            } else {
                Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillButton("Mettre à jour", { vm.startUpdate() }, Modifier.focusRequester(first), primary = true)
                    PillButton("Plus tard", { vm.skipUpdate() })
                }
                Text("BibiTV se fermera pendant l'installation puis redémarrera à jour.", color = C.Text3, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
