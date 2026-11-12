plugins {
    java
}

group = "fr.alexuslab.paperseasons"
version = "1.3.0"
description = "Système de saisons réaliste O(1) v1.3.0 (Artisanat thermique, Cueillettes, Bulletin météo, Fonte active, Almanach GUI, Événements) pour Raspberry Pi 5"

repositories {
    mavenCentral()
    // Dépôt officiel PaperMC
    maven("https://repo.papermc.io/repository/maven-public/")
    // Dépôt CodeMC (PacketEvents - pour le hook optionnel ChunkBiomeData de l'Étape 3)
    maven("https://repo.codemc.io/repository/maven-releases/")
    // Dépôt dmulloy2 (ProtocolLib - alternative optionnelle pour l'Étape 3)
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    // Librairies exactes extraites du serveur PaperMC Pi 5 (compilation hors-ligne garantie)
    compileOnly(fileTree("libs") { include("*.jar") })

    // API PaperMC (inclut nativement Kyori Adventure & MiniMessage)
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Netty (embarqué nativement dans le runtime PaperMC, utilisé pour le ChannelDuplexHandler autonome)
    compileOnly("io.netty:netty-all:4.1.112.Final")

    // Hooks réseau optionnels (Étape 3 : réécriture à la volée des biomes clients sans toucher au disque/Terralith)
    compileOnly("com.github.retrooper:packetevents-spigot:2.6.0")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

// Tâche utilitaire pour empaqueter le Data Pack compagnon dans e:/minecraft/packages/paper_seasons_dp.zip
tasks.register<Zip>("packageDatapack") {
    group = "build"
    description = "Empaquette le Data Pack paper_seasons_dp au format .zip prêt pour le serveur Pi 5"
    from(layout.projectDirectory.dir("paper_seasons_dp"))
    archiveFileName.set("paper_seasons_dp.zip")
    destinationDirectory.set(file("e:/minecraft/packages"))
}
