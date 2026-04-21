plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation("org.jmdns:jmdns:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("space.diomentia.ehzbackend.discovery.MainKt")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

// The `application` plugin's distTar/distZip/startScripts consume the main jar output,
// which collides with shadowJar's empty classifier. We only need shadowJar for the Docker image,
// so disable the dist tasks to keep the build graph clean.
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }
tasks.named("startScripts") { enabled = false }
tasks.named("jar") { enabled = false }
