import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "9.2.2" // produces the single runnable .jar
}

group = "com.deylauncher"
// Single source of truth for the app version. It is baked into the jar/resource that
// AppUpdater.currentVersion() reads at runtime, so the self-updater always knows exactly
// which version is installed -- and CI's jpackage step discovers this same fat jar by name.
version = "0.1.9"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Without this, javac reads .java files using the *platform default* charset instead of the
// charset they're actually saved in (UTF-8). On Linux CI runners the platform default already
// happens to be UTF-8, so it's easy to miss -- but on Windows (JDK 17 predates JEP 400's
// UTF-8-by-default, which only landed in JDK 18) the default is the machine's ANSI codepage
// (e.g. Windows-1252), so any non-ASCII literal baked into source (the "·" separator dot in
// server/profile labels, the "▸" arrow, etc.) gets silently mis-decoded at compile time and
// bakes a mangled character into the compiled class -- which then shows up wrong at runtime on
// every device running that particular build, since the corruption happened once, at compile
// time, not per-machine at render time. This is exactly the class of bug the Circle-based
// status dots (see LauncherApp#statusLabel/#badgeLabel) were fixed to avoid for glyphs -- this
// setting is the equivalent fix for plain text literals: force UTF-8 on every JavaCompile task,
// on every OS, so what's saved in the source is what actually ends up in the jar.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

javafx {
    version = "21.0.2"
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    // JSON parsing (version manifest, MS/Xbox/Minecraft API responses, GitHub API responses)
    implementation("com.google.code.gson:gson:2.11.0")
    // WebP image support for Modrinth icons (TwelveMonkeys ImageIO)
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    // JNA: OPTIONAL native mouse fallback. Off-edge window parking is implemented in pure JavaFX --
    // PointerProbe reads the global pointer with javafx.scene.robot.Robot#getMousePosition() and the
    // drag/release events do the rest -- so this dependency is NOT required for the feature. Where the
    // native read IS available (Windows / X11), PointerProbe additionally gets the GLOBAL primary-button
    // state, which lets a drag that is pinned against a screen edge end on the true mouse-up even if no
    // JavaFX release event ever arrives. Where it isn't (e.g. Wayland) NativeInput reports unsupported
    // and PointerProbe simply stays on the pure-JavaFX tier.
    implementation("net.java.dev.jna:jna:5.14.0")
    // HTTP client is java.net.http (built into Java 17), no extra dependency needed
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // JUnit Platform launcher makes Gradle's test executor able to actually RUN the tests
    // (junit-bom + junit-jupiter bring the API/engine but not the gradle-side launcher).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.deylauncher.ui.LauncherApp")
}

tasks.withType<JavaExec>().configureEach {
    mainClass.set("com.deylauncher.ui.LauncherApp")
}

// Gradle's JavaExec 'run' task does NOT forward the parent process's stdin by
// default -- it has to be wired explicitly, or Scanner.nextLine() inside the
// launched app throws NoSuchElementException even when you pipe input in via
// `echo "name" | ./gradlew run`.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Console-only entry point (no window) -- handy for quick debugging without
// spinning up JavaFX. Run with: ./gradlew runConsole
tasks.register<JavaExec>("runConsole") {
    group = "application"
    mainClass.set("com.deylauncher.Main")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("DeyLauncher")
    archiveClassifier.set("")
    archiveVersion.set(version.toString())
}

tasks.processResources {
    // Single source of truth for the self-updater's version: bake project.version (above) into a
    // tiny resource the running app reads, so AppUpdater.currentVersion() is correct in BOTH the
    // packaged app and `./gradlew run` -- regardless of how the jar happens to be named.
    val versionFile = file("build/generated-version/deylauncher-version.properties")
    doFirst {
        val p = versionFile.toPath()
        Files.createDirectories(p.getParent())
        Files.writeString(p, "version=${project.version}\n")
        logger.lifecycle("DeyLauncher: embedding app version ${project.version} for the self-updater.")
    }
    from(versionFile)

}

// ---------------------------------------------------------------------------------------------
// Shared GitHub backend credentials (Friends / capes / option kits / friend-visible servers).
// ---------------------------------------------------------------------------------------------
// Baked into the app so a freshly downloaded launcher has all of those working with NO key and NO
// file from the user. Source priority:
//
//   1. the CI secret, exposed as the environment variable DEYLAUNCHER_GITHUB_PROPS
//      (GitHub -> Settings -> Secrets and variables -> Actions). This is why no token is ever
//      committed to the repository.
//   2. secrets/embedded-github.properties on the developer's own machine (git-ignored).
//
// The value is XOR+Base64'd before it is written, so it is not a plain "github_pat_..." string
// sitting inside the shipped jar. That is OBFUSCATION, NOT SECURITY: any desktop app can be
// unpacked and reversed by whoever holds it. What actually contains the damage is that this is a
// fine-grained token scoped to ONE repo (onpishi/DeyLauncher-Friends, Contents read/write) with a
// short expiry -- see GITHUB_SETUP.md. Never widen that scope, and rotate it if it leaks.
val embeddedBackendFile = layout.buildDirectory.file("generated-backend/deylauncher-backend.dat")
val embeddedBackendKey = "DeyLauncher-backend-v1"

/** XOR with the fixed key, then Base64. Must stay byte-for-byte compatible with GitHubConfig.deobfuscate. */
fun packEmbeddedBackend(propsText: String): String {
    val key = embeddedBackendKey.toByteArray(Charsets.UTF_8)
    val raw = propsText.toByteArray(Charsets.UTF_8)
    val out = ByteArray(raw.size)
    for (i in raw.indices) out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
    return Base64.getEncoder().encodeToString(out)
}

val embedGithubCredentials = tasks.register("embedGithubCredentials") {
    val outFile = embeddedBackendFile.get().asFile
    outputs.file(outFile)
    // Always regenerate: the value may come from a secret, and registering a secret as a task input
    // would leak it into build logs / build scans / the up-to-date checksum.
    outputs.upToDateWhen { false }

    doLast {
        val fromEnv = System.getenv("DEYLAUNCHER_GITHUB_PROPS")
        val secretFile = file("secrets/embedded-github.properties")
        val raw = when {
            !fromEnv.isNullOrBlank() -> fromEnv
            secretFile.isFile -> secretFile.readText()
            else -> null
        }
        if (raw.isNullOrBlank()) {
            outFile.delete()
            logger.lifecycle("DeyLauncher: no shared GitHub backend configured for this build -- " +
                    "Friends will show 'not set up'. Set the DEYLAUNCHER_GITHUB_PROPS secret " +
                    "(CI) or create secrets/embedded-github.properties (local).")
            return@doLast
        }
        // A bare token is accepted too, so the secret only has to hold the PAT itself.
        val propsText = if (raw.contains('=')) raw.trim() + "\n" else
            "token=${raw.trim()}\nowner=onpishi\nrepo=DeyLauncher-Friends\nfriendsPath=friends.json\n"
        outFile.parentFile.mkdirs()
        outFile.writeText(packEmbeddedBackend(propsText))
        logger.lifecycle("DeyLauncher: embedded the shared GitHub backend into this build " +
                "(the value itself is never printed).")
    }
}

tasks.processResources {
    dependsOn(embedGithubCredentials)
    // Lands at the classpath root, which is where GitHubConfig looks for it.
    from(embeddedBackendFile) { rename { "deylauncher-backend.dat" } }
}

tasks.register("printClasspath") {
    doLast {
        configurations.runtimeClasspath.get().files.forEach {
            println(it.absolutePath)
        }
    }
}
tasks.register<Sync>("prepareJpackage") {
    dependsOn(tasks.shadowJar, "prepareJpackageFx")

    // The jpackage `--input` gets ONLY the fat shadow jar. That fat jar already bundles every
    // compile dependency (gson, the TwelveMonkeys imageio-* modules, JavaFX classes). Shipping
    // the individual modular jars alongside it as well caused a split-package module clash --
    // the packaged app failed at JVM boot with:
    //   ResolutionException: Modules DeyLauncher and com.twelvemonkeys.imageio.metadata export
    //   package com.twelvemonkeys.imageio.metadata.xmp to module com.twelvemonkeys.imageio.core
    // (introduced by adding imageio-webp; this is exactly the packaging regression the launcher
    //  hit in the CapesUpdate release).
    from(tasks.shadowJar)

    into(layout.buildDirectory.dir("jpackage-input"))
}

// JavaFX only needs to be available as build-time MODULES (passed to `jpackage --module-path`
// alongside `--add-modules javafx.controls,javafx.graphics`) so jlink embeds JavaFX's native
// platform code into the bundled runtime. These modular jars are NOT shipped into the app-image
// classpath (the fat jar holds the JavaFX classes themselves), so they never sit next to the fat
// jar and can't conflict with it.
tasks.register<Sync>("prepareJpackageFx") {
    dependsOn(tasks.shadowJar)

    from(configurations.runtimeClasspath) {
        include("javafx-*.jar")
    }

    into(layout.buildDirectory.dir("jpackage-fx"))
}

tasks.named("distZip") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.named("distTar") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.named("startScripts") {
    dependsOn(tasks.named("shadowJar"))
}
tasks.named("startShadowScripts") {
    dependsOn(tasks.named("jar"))
}
