# BibiTV

Lecteur IPTV **Xtream Codes** pour Windows, avec une interface épurée inspirée de l'Apple TV.

- **Accueil** : à la une, Reprendre, Top 10 des films et des séries de la semaine, Pour toi, Top 10 des chaînes les plus regardées en France, chaînes récentes, Ma liste, nouveautés
- **Reprendre** : historique des films/séries/chaînes, reprise à la seconde près (et à l'épisode suivant pour les séries)
- **Déjà vu** : pastille « VU » automatique (> 90 % regardé) ou manuelle, bouton « Masquer les vus »
- **Pour toi** : 👍 / 👎 sur les fiches et écran « Personnaliser » ; suggestions de films et séries selon vos genres préférés
- **Programme TV** : en cours / ensuite au survol d'une chaîne (guide EPG du fournisseur)
- **En direct / Films / Séries** : classés selon les catégories du fournisseur
- **Chaînes par marque** : Canal+, beIN Sports, RMC Sport, Eurosport, DAZN, Ligue 1+, L'Équipe, Infos… pour retrouver vite les matchs
- **Jingle d'ouverture** silencieux avec le logo BibiTV (cliquer pour passer)
- **Récemment ajoutés** : les films et séries triés par date d'ajout
- **Doublons fusionnés** : « Verity (2026) 4K MULTI », « FHD MULTI » et « SUBT AR » deviennent une seule affiche ; la version (qualité / langue) se choisit sur la fiche
- **Téléchargements** : films, épisodes ou saisons entières sur le PC (dossier `Vidéos\BibiTV` par défaut, modifiable), avec suivi de la progression
- **Fiche détail** : affiche, fond, synopsis, genre, durée, note, casting
- **Séries** : saisons et épisodes ; l'épisode suivant se lance tout seul
- **Ma liste** : favoris (bouton sur la fiche, ou clic droit sur une tuile)
- **Recherche instantanée** dans l'onglet en cours (Ctrl+F), sans tenir compte des accents
- **Lecteur basé sur VLC** (LibVLC) : TS, HLS, MKV, HEVC, AC3… avec choix de la **langue audio** et des **sous-titres**
- Zapping entre les chaînes avec Page préc. / Page suiv.
- **Mise à jour intégrée** : au démarrage (ou en cliquant sur le numéro de version), BibiTV vérifie la dernière release GitHub, la télécharge, vérifie son empreinte SHA-256 et redémarre à jour
- Mot de passe **chiffré avec DPAPI** (lisible uniquement par votre compte Windows), dans `%APPDATA%\BibiTv\settings.json`

## Android TV

L'application Android TV (dossier `android/`) reprend les mêmes fonctions, pilotées à la télécommande.

- **Installation sur la TV** : dans l'app *Downloader*, saisir
  `https://github.com/Bilou691/BibiTv/releases/download/android/bibitv.apk`
- **Mises à jour** : chaque push sur la **branche par défaut** du dépôt publie la release `android-v<version>`
  (version lue dans `android/app/build.gradle.kts`) ; les TV la proposent au démarrage.
  Les autres branches compilent l'APK (artefact du workflow) sans le diffuser.
- **Pour diffuser une mise à jour, augmentez `versionCode` et `versionName`** dans `android/app/build.gradle.kts`.
- Secrets GitHub utilisés : `ANDROID_KEYSTORE_B64` et `ANDROID_KEYSTORE_PASSWORD` (signature, indispensables
  pour que les mises à jour s'installent par-dessus), `TMDB_KEY` (Top 10).

## Installation (Windows)

1. Ouvrez l'onglet **Actions** du dépôt, puis le dernier run **Build Windows** réussi
2. Téléchargez l'artefact `BibiTv-win-x64` et dézippez-le
3. Lancez `BibiTv.exe` (aucun prérequis : .NET et VLC sont embarqués)

Chaque build publie (ou met à jour) la release correspondant à `<Version>` dans `src/BibiTv/BibiTv.csproj`.
**Pour diffuser une mise à jour aux applis installées, augmentez ce numéro de version** : elles la proposeront au prochain démarrage.

## Top 10 de la semaine (TMDB)

Sans configuration, le Top 10 reprend les nouveautés de la semaine les mieux notées du catalogue.
Pour afficher les **vraies tendances de la semaine**, ajoutez une clé TMDB au dépôt (elle n'apparaît jamais dans le code) :

1. Créez un compte gratuit sur [themoviedb.org](https://www.themoviedb.org/signup), puis **Paramètres → API**, et copiez le « Jeton d'accès en lecture à l'API »
2. Sur GitHub : **Settings → Secrets and variables → Actions → New repository secret**, nom `TMDB_KEY`, valeur = le jeton
3. Relancez le workflow : la clé est intégrée à l'exe au moment de la compilation

_Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB._

## Connexion

Sur le premier écran, renseignez :

| Champ | Exemple |
|---|---|
| Adresse du serveur | `http://mon-serveur.com:8080` |
| Nom d'utilisateur | celui de votre abonnement |
| Mot de passe | celui de votre abonnement |

> Ne commitez jamais vos identifiants dans ce dépôt.

## Raccourcis du lecteur

| Touche | Action |
|---|---|
| Espace / K | Lecture / pause |
| ← / → | −10 s / +30 s |
| ↑ / ↓ ou molette | Volume |
| Page préc. / Page suiv. | Chaîne ou épisode précédent / suivant |
| F / F11 / double-clic | Plein écran |
| M | Muet |
| Échap | Quitter le plein écran, puis fermer le lecteur |

## Compiler soi-même

Prérequis : [SDK .NET 8](https://dotnet.microsoft.com/download/dotnet/8.0) sur Windows.

```bash
dotnet run --project src/BibiTv
```

```bash
dotnet publish src/BibiTv -c Release -r win-x64 --self-contained true -o publish
```

## Structure

```
src/BibiTv/
├── Models/Models.cs            Catégories, films, séries, épisodes
├── Services/XtreamClient.cs    API player_api.php (parsing tolérant aux panels capricieux)
├── Services/SettingsService.cs Réglages + mot de passe chiffré DPAPI
├── Services/TitleGrouper.cs    Fusion des doublons (4K, FHD, VOSTFR…)
├── Services/DownloadManager.cs File de téléchargements
├── Services/UpdateService.cs   Mise à jour depuis les releases GitHub
├── Services/TopService.cs      Top 10 de la semaine (TMDB ou catalogue)
├── Services/ChannelBrands.cs   Chaînes par marque + Top 10 des chaînes en France
├── Assets/                     Logo et icône
├── Themes/Styles.xaml          Thème sombre façon tvOS
├── Views/MainWindow.*          Connexion, navigation, fiche détail
└── Views/PlayerWindow.*        Lecteur LibVLC plein écran
```

## Android TV

Le dossier `android/` contient la même application pour Android TV (Kotlin, Jetpack Compose, LibVLC) :
décodage matériel 4K HEVC, adaptation de la fréquence de l'écran (24p), télécommande, téléchargements
vers une clé USB, mise à jour intégrée.

**Installer sur la TV avec l'app Downloader** : saisir
`https://github.com/Bilou691/BibiTv/releases/download/android/bibitv.apk`

Secrets GitHub utilisés par le workflow `Build Android TV` :

| Secret | Rôle |
|---|---|
| `ANDROID_KEYSTORE_B64` | clé de signature (keystore encodé en Base64) — indispensable pour les mises à jour |
| `ANDROID_KEYSTORE_PASSWORD` | mot de passe de la clé |
| `TMDB_KEY` | jeton TMDB (Top 10 de la semaine) |
