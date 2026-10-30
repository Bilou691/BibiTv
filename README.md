# BibiTV

Lecteur IPTV **Xtream Codes** pour Windows, avec une interface épurée inspirée de l'Apple TV.

- **En direct / Films / Séries** : trois onglets, classés selon les catégories du fournisseur
- **Récemment ajoutés** : les films et séries triés par date d'ajout
- **Doublons fusionnés** : « Verity (2026) 4K MULTI », « FHD MULTI » et « SUBT AR » deviennent une seule affiche ; la version (qualité / langue) se choisit sur la fiche
- **Téléchargements** : films, épisodes ou saisons entières sur le PC (dossier `Vidéos\BibiTV` par défaut, modifiable), avec suivi de la progression
- **Fiche détail** : affiche, fond, synopsis, genre, durée, note, casting
- **Séries** : saisons et épisodes ; l'épisode suivant se lance tout seul
- **Ma liste** : favoris (bouton sur la fiche, ou clic droit sur une tuile)
- **Recherche instantanée** dans l'onglet en cours (Ctrl+F), sans tenir compte des accents
- **Lecteur basé sur VLC** (LibVLC) : TS, HLS, MKV, HEVC, AC3… avec choix de la **langue audio** et des **sous-titres**
- Zapping entre les chaînes avec Page préc. / Page suiv.
- Mot de passe **chiffré avec DPAPI** (lisible uniquement par votre compte Windows), dans `%APPDATA%\BibiTv\settings.json`

## Installation

1. Ouvrez l'onglet **Actions** du dépôt, puis le dernier run **Build Windows** réussi
2. Téléchargez l'artefact `BibiTv-win-x64` et dézippez-le
3. Lancez `BibiTv.exe` (aucun prérequis : .NET et VLC sont embarqués)

Pour créer une release téléchargeable : poussez un tag `v1.0.0`. Le workflow joint alors le zip à une release GitHub.

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
├── Themes/Styles.xaml          Thème sombre façon tvOS
├── Views/MainWindow.*          Connexion, navigation, fiche détail
└── Views/PlayerWindow.*        Lecteur LibVLC plein écran
```
