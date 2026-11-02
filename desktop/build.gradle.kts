import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// Version commune à toutes les plateformes (fichier version.properties à la racine du dépôt).
val appVersion = java.util.Properties().apply { rootDir.resolve("../version.properties").inputStream().use { load(it) } }
val versionName: String = appVersion.getProperty("versionName")
version = versionName

// Clé TMDB injectée à la compilation (secret GitHub), jamais écrite dans le code.
val tmdbKey: String = (System.getenv("TMDB_KEY") ?: "").replace("\"", "")

kotlin {
    jvmToolchain(17)
    // Code commun avec l'app Android (modèles, regroupement des doublons, marques de chaînes)
    sourceSets["main"].kotlin.srcDir("../shared/src")
}

// Informations de build lues au démarrage (version, clé TMDB)
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val v = versionName
    val k = tmdbKey
    inputs.property("version", v)
    inputs.property("tmdb", k)
    outputs.dir(out)
    doLast {
        val f = out.get().file("bibitv-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$v\ntmdb=$k\n")
    }
}
sourceSets["main"].resources.srcDir(generateBuildInfo)

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Lecteur vidéo : VLC (LibVLC) via vlcj
    implementation("uk.co.caprica:vlcj:4.8.3")
    // Chiffrement du mot de passe sous Windows (DPAPI)
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

compose.desktop {
    application {
        mainClass = "com.bibitv.tv.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            // « BibiTv » : le nom de l'exécutable Windows (BibiTv.exe) reste celui de l'ancienne app,
            // ce qui permet à sa mise à jour intégrée de basculer vers cette version.
            packageName = "BibiTv"
            packageVersion = versionName
            description = "Lecteur IPTV Xtream Codes"
            vendor = "BibiTV"
            copyright = "BibiTV"
            includeAllModules = true
            // VLC embarqué pour Windows (déposé par la CI dans appResources/windows/vlc)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))

            macOS {
                bundleID = "com.bibitv.desktop"
                dockName = "BibiTV"
                val icns = project.file("icons/BibiTv.icns")
                if (icns.exists()) iconFile.set(icns)
            }
            linux {
                iconFile.set(project.file("icons/BibiTv.png"))
                debMaintainer = "bibitv@users.noreply.github.com"
                menuGroup = "AudioVideo"
                appCategory = "video"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/BibiTv.ico"))
                menuGroup = "BibiTV"
                shortcut = true
                dirChooser = true
                upgradeUuid = "8f0b6a52-3c55-4a9e-9a77-5b1d2f1e7c40"
            }
        }
    }
}
