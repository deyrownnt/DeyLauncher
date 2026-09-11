plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "9.2.2" // produces the single runnable .jar
}

group = "com.deylauncher"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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

// Bakes a GitHub token into the built jar for distributed installers, so friends who install
// the packaged app don't need to set up github.properties themselves -- only the dev building
// the installer does. Reads from a LOCAL, gitignored file (never committed, never in chat):
//   secrets/embedded-github.properties
// Same format as ~/.deylauncher/github.properties -- see GITHUB_SETUP.md.
// If that file doesn't exist (e.g. building from source without setting this up), this is a
// silent no-op and GitHubConfig just falls back to each user's own local override file instead.
tasks.processResources {
    val embedSource = file("secrets/embedded-github.properties")
    if (embedSource.exists()) {
        from(embedSource) {
            rename { "embedded-github.properties" }
        }
        logger.lifecycle("DeyLauncher: embedding GitHub token from secrets/embedded-github.properties into this build.")
    } else {
        logger.lifecycle("DeyLauncher: no secrets/embedded-github.properties found -- building WITHOUT an embedded token (users will need their own github.properties for Friends).")
    }
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