// Release pipeline for ehz-backend.
//
// Invoked as: ./gradlew release -Pehz.version=1.2.3
//
// Builds the two Docker images (app, discovery), optionally pushes them to
// Docker Hub (skipped when DOCKERHUB_USERNAME/DOCKERHUB_TOKEN are absent),
// and packages an air-gap ZIP containing image tarballs, the operator compose
// file, and SHA256SUMS.
//
// `publishGithubRelease` is a sibling task, not wired into `release`.

val releaseVersion = project.version.toString()
val repoRoot = rootProject.layout.projectDirectory
val releaseDir = rootProject.layout.buildDirectory.dir("release")
val imagesDir = rootProject.layout.buildDirectory.dir("release/images")
val stagingDir = rootProject.layout.buildDirectory.dir("release/stage")

val appImageRepo = "diomentia/ehz-backend-app"
val discoveryImageRepo = "diomentia/ehz-backend-discovery"
val appImageTag = "$appImageRepo:$releaseVersion"
val discoveryImageTag = "$discoveryImageRepo:$releaseVersion"

val appBootJar = tasks.getByPath(":app:bootJar")
val discoveryShadowJar = tasks.getByPath(":discovery:shadowJar")

// ---- build images -----------------------------------------------------------

val buildAppImage by tasks.registering(Exec::class) {
    group = "release"
    description = "Build the app Docker image and tag it with the release version + latest."
    dependsOn(appBootJar)
    inputs.files(appBootJar.outputs.files)
    inputs.file(repoRoot.file("app/Dockerfile"))
    inputs.file(repoRoot.file("app/docker-entrypoint.sh"))
    // Stamp a marker file when the build succeeds so Gradle can up-to-date check.
    val marker = releaseDir.map { it.file("markers/app-image-$releaseVersion.stamp") }
    outputs.file(marker)
    workingDir = repoRoot.asFile
    commandLine(
        "docker", "build",
        "-f", "app/Dockerfile",
        "-t", appImageTag,
        "-t", "$appImageRepo:latest",
        ".",
    )
    doFirst {
        marker.get().asFile.parentFile.mkdirs()
    }
    doLast {
        marker.get().asFile.writeText("$appImageTag\n")
    }
}

val buildDiscoveryImage by tasks.registering(Exec::class) {
    group = "release"
    description = "Build the discovery Docker image and tag it with the release version + latest."
    dependsOn(discoveryShadowJar)
    inputs.files(discoveryShadowJar.outputs.files)
    inputs.file(repoRoot.file("discovery/Dockerfile"))
    val marker = releaseDir.map { it.file("markers/discovery-image-$releaseVersion.stamp") }
    outputs.file(marker)
    workingDir = repoRoot.asFile
    commandLine(
        "docker", "build",
        "-f", "discovery/Dockerfile",
        "-t", discoveryImageTag,
        "-t", "$discoveryImageRepo:latest",
        ".",
    )
    doFirst {
        marker.get().asFile.parentFile.mkdirs()
    }
    doLast {
        marker.get().asFile.writeText("$discoveryImageTag\n")
    }
}

val buildImages by tasks.registering {
    group = "release"
    description = "Build both Docker images."
    dependsOn(buildAppImage, buildDiscoveryImage)
}

// ---- push images ------------------------------------------------------------

val dockerhubUsername = providers.environmentVariable("DOCKERHUB_USERNAME")
val dockerhubToken = providers.environmentVariable("DOCKERHUB_TOKEN")

fun dockerLogin(username: String, token: String) {
    val pb = ProcessBuilder("docker", "login", "-u", username, "--password-stdin")
        .redirectErrorStream(true)
    val proc = pb.start()
    proc.outputStream.use { it.write(token.toByteArray()) }
    val out = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) {
        throw GradleException("docker login failed (exit $code): $out")
    }
}

fun dockerLogout() {
    val pb = ProcessBuilder("docker", "logout").redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.bufferedReader().readText() // drain, ignore
    proc.waitFor() // ignore failure
}

fun org.gradle.api.Task.dockerhubCredsPresent(): Boolean {
    val u = dockerhubUsername.orNull
    val t = dockerhubToken.orNull
    val ok = !u.isNullOrBlank() && !t.isNullOrBlank()
    if (!ok) {
        logger.lifecycle("skipping $name: DOCKERHUB_USERNAME / DOCKERHUB_TOKEN not set")
    }
    return ok
}

val pushAppImage by tasks.registering(Exec::class) {
    group = "release"
    description = "docker push the app image (version + latest tags). Skipped if DOCKERHUB_* unset."
    dependsOn(buildAppImage)
    onlyIf { dockerhubCredsPresent() }
    workingDir = repoRoot.asFile
    commandLine(
        "bash", "-c",
        """docker push "$appImageTag" && docker push "$appImageRepo:latest"""",
    )
    doFirst {
        val u = dockerhubUsername.get()
        val t = dockerhubToken.get()
        dockerLogin(u, t)
    }
    doLast {
        dockerLogout()
    }
}

val pushDiscoveryImage by tasks.registering(Exec::class) {
    group = "release"
    description = "docker push the discovery image (version + latest tags). Skipped if DOCKERHUB_* unset."
    dependsOn(buildDiscoveryImage)
    onlyIf { dockerhubCredsPresent() }
    workingDir = repoRoot.asFile
    commandLine(
        "bash", "-c",
        """docker push "$discoveryImageTag" && docker push "$discoveryImageRepo:latest"""",
    )
    doFirst {
        val u = dockerhubUsername.get()
        val t = dockerhubToken.get()
        dockerLogin(u, t)
    }
    doLast {
        dockerLogout()
    }
}

val pushImages by tasks.registering {
    group = "release"
    description = "Push both Docker images. Skipped if DOCKERHUB_* unset."
    dependsOn(pushAppImage, pushDiscoveryImage)
}

// ---- save images to tarballs ------------------------------------------------

val appTarball = imagesDir.map { it.file("ehz-backend-app-$releaseVersion.tar.gz") }
val discoveryTarball = imagesDir.map { it.file("ehz-backend-discovery-$releaseVersion.tar.gz") }

val saveAppImage by tasks.registering(Exec::class) {
    group = "release"
    description = "Save the app image as a gzipped tar under build/release/images/."
    dependsOn(buildAppImage)
    inputs.files(buildAppImage.get().outputs.files)
    outputs.file(appTarball)
    workingDir = repoRoot.asFile
    val out = appTarball.get().asFile.absolutePath
    commandLine(
        "bash", "-c",
        """docker save "$appImageTag" | gzip > "$out"""",
    )
    doFirst {
        appTarball.get().asFile.parentFile.mkdirs()
    }
}

val saveDiscoveryImage by tasks.registering(Exec::class) {
    group = "release"
    description = "Save the discovery image as a gzipped tar under build/release/images/."
    dependsOn(buildDiscoveryImage)
    inputs.files(buildDiscoveryImage.get().outputs.files)
    outputs.file(discoveryTarball)
    workingDir = repoRoot.asFile
    val out = discoveryTarball.get().asFile.absolutePath
    commandLine(
        "bash", "-c",
        """docker save "$discoveryImageTag" | gzip > "$out"""",
    )
    doFirst {
        discoveryTarball.get().asFile.parentFile.mkdirs()
    }
}

// ---- staging + SHA256SUMS ---------------------------------------------------

// Copy the image tarballs and the three root-level shipped files into a staging
// directory whose layout matches what ends up inside the ZIP. sha256sum then
// runs with workingDir = staging dir and records paths relative to it, so
// `sha256sum -c SHA256SUMS` works as soon as the ZIP is extracted.
val stageRelease by tasks.registering(Copy::class) {
    group = "release"
    description = "Assemble the release staging directory that the ZIP ships."
    dependsOn(saveAppImage, saveDiscoveryImage)
    from(appTarball) { into("images") }
    from(discoveryTarball) { into("images") }
    from(repoRoot.file("docker-compose.yml"))
    from(repoRoot.file(".env.example"))
    from(repoRoot.file("README.md"))
    from(repoRoot.file("ИНСТРУКЦИЯ.md"))
    from(repoRoot.file("setup.sh"))
    from(repoRoot.file("setup.ps1"))
    from(repoRoot.file("data/backup.sh")) { into("data") }
    from(repoRoot.file("data/backup.ps1")) { into("data") }
    from(repoRoot.file("data/restore.sh")) { into("data") }
    from(repoRoot.file("data/restore.ps1")) { into("data") }
    into(stagingDir)
}

val generateSha256Sums by tasks.registering(Exec::class) {
    group = "release"
    description = "Write SHA256SUMS alongside the staged release files."
    dependsOn(stageRelease)
    // Track the staged payload as inputs but exclude the SHA256SUMS file itself
    // to avoid a self-referential dependency that would mark the task always-out-of-date.
    inputs.files(fileTree(stagingDir) { exclude("SHA256SUMS") })
    val sumsFile = stagingDir.map { it.file("SHA256SUMS") }
    outputs.file(sumsFile)
    workingDir = stagingDir.get().asFile
    commandLine(
        "bash", "-c",
        "sha256sum images/*.tar.gz docker-compose.yml .env.example README.md 'ИНСТРУКЦИЯ.md' setup.sh setup.ps1 data/backup.sh data/backup.ps1 data/restore.sh data/restore.ps1 > SHA256SUMS",
    )
}

// ---- ZIP --------------------------------------------------------------------

val bundleRelease by tasks.registering(Zip::class) {
    group = "release"
    description = "Build the air-gap release ZIP."
    dependsOn(generateSha256Sums)
    archiveFileName.set("ehz-backend-$releaseVersion.zip")
    destinationDirectory.set(releaseDir)
    from(stagingDir)
}

// ---- GitHub release (sibling task, not called by `release`) -----------------

val publishGithubRelease by tasks.registering(Exec::class) {
    group = "release"
    description = "Create a GitHub release with the bundled ZIP attached (requires `gh` on PATH)."
    dependsOn(bundleRelease)
    val zipFile = bundleRelease.get().archiveFile
    inputs.file(zipFile)
    workingDir = repoRoot.asFile
    commandLine(
        "gh", "release", "create",
        "v$releaseVersion",
        zipFile.get().asFile.absolutePath,
        "--title", "ehz-backend $releaseVersion",
        "--notes", "Release $releaseVersion",
    )
    doFirst {
        val path = System.getenv("PATH") ?: ""
        val found = path.split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { java.io.File(it, "gh") }
            .any { it.isFile && it.canExecute() }
        if (!found) {
            throw GradleException(
                "`gh` CLI not found on PATH. Install it from https://cli.github.com/ or adjust PATH.",
            )
        }
    }
}

// ---- entry point ------------------------------------------------------------

tasks.register("release") {
    group = "release"
    description = "Build images, push (if credentials), and bundle the air-gap ZIP."
    dependsOn(buildImages, pushImages, bundleRelease)
}
