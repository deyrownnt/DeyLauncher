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
    // HTTP client is java.net.http (built into Java 17), no extra dependency needed
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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
    dependsOn(tasks.shadowJar)

    from(tasks.shadowJar)
    from(configurations.runtimeClasspath)

    into(layout.buildDirectory.dir("jpackage-input"))
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