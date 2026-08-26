plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.5" // produces the single runnable .jar
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
    mainClass.set("com.deylauncher.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("DeyLauncher")
    archiveClassifier.set("")
    archiveVersion.set(version.toString())
}
