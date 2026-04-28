// Release pipeline for ehz-backend.
//
// Invoked as: ./gradlew release -Pehz.version=1.2.3
//
// Builds the two Docker images (app, discovery), optionally pushes them to
// Docker Hub (skipped when DOCKERHUB_USERNAME/DOCKERHUB_TOKEN are absent),
// and packages two air-gap ZIPs (linux / windows) containing image tarballs,
// the operator compose file, platform scripts, and SHA256SUMS.
//
// `publishGithubRelease` is a sibling task, not wired into `release`.

val releaseVersion = project.version.toString()
val repoRoot = rootProject.layout.projectDirectory
val releaseDir = rootProject.layout.buildDirectory.dir("release")
val imagesDir = rootProject.layout.buildDirectory.dir("release/images")
val linuxStagingDir = rootProject.layout.buildDirectory.dir("release/stage-linux")
val windowsStagingDir = rootProject.layout.buildDirectory.dir("release/stage-windows")

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

// Each platform gets its own staging directory whose layout matches what ends
// up inside the ZIP. sha256sum runs with workingDir = staging dir and records
// paths relative to it, so `sha256sum -c SHA256SUMS` works after extraction.

val sharedFiles: CopySpec.() -> Unit = {
    from(appTarball) { into("img") }
    from(discoveryTarball) { into("img") }
    from(repoRoot.file("docker-compose.yml"))
    from(repoRoot.file(".env.example"))
}

val stageLinuxRelease by tasks.registering(Copy::class) {
    group = "release"
    description = "Assemble the Linux release staging directory."
    dependsOn(saveAppImage, saveDiscoveryImage)
    sharedFiles()
    from(repoRoot.file("linux/ИНСТРУКЦИЯ.md"))
    from(repoRoot.file("linux/setup.sh"))
    from(repoRoot.file("linux/backup.sh")) { into("tools") }
    from(repoRoot.file("linux/restore.sh")) { into("tools") }
    into(linuxStagingDir)
}

val stageWindowsRelease by tasks.registering(Copy::class) {
    group = "release"
    description = "Assemble the Windows release staging directory."
    dependsOn(saveAppImage, saveDiscoveryImage)
    sharedFiles()
    from(repoRoot.file("windows/ИНСТРУКЦИЯ.md"))
    from(repoRoot.file("windows/setup.ps1"))
    from(repoRoot.file("windows/backup.ps1")) { into("tools") }
    from(repoRoot.file("windows/restore.ps1")) { into("tools") }
    into(windowsStagingDir)
}

val generateLinuxSha256Sums by tasks.registering(Exec::class) {
    group = "release"
    description = "Write SHA256SUMS for the Linux release."
    dependsOn(stageLinuxRelease)
    inputs.files(fileTree(linuxStagingDir) { exclude("SHA256SUMS") })
    val sumsFile = linuxStagingDir.map { it.file("SHA256SUMS") }
    outputs.file(sumsFile)
    workingDir = linuxStagingDir.get().asFile
    commandLine(
        "bash", "-c",
        "sha256sum img/*.tar.gz docker-compose.yml .env.example 'ИНСТРУКЦИЯ.md' setup.sh tools/backup.sh tools/restore.sh > SHA256SUMS",
    )
}

val generateWindowsSha256Sums by tasks.registering(Exec::class) {
    group = "release"
    description = "Write SHA256SUMS for the Windows release."
    dependsOn(stageWindowsRelease)
    inputs.files(fileTree(windowsStagingDir) { exclude("SHA256SUMS") })
    val sumsFile = windowsStagingDir.map { it.file("SHA256SUMS") }
    outputs.file(sumsFile)
    workingDir = windowsStagingDir.get().asFile
    commandLine(
        "bash", "-c",
        "sha256sum img/*.tar.gz docker-compose.yml .env.example 'ИНСТРУКЦИЯ.md' setup.ps1 tools/backup.ps1 tools/restore.ps1 > SHA256SUMS",
    )
}

// ---- ZIPs -------------------------------------------------------------------

val bundleLinuxRelease by tasks.registering(Zip::class) {
    group = "release"
    description = "Build the Linux air-gap release ZIP."
    dependsOn(generateLinuxSha256Sums)
    archiveFileName.set("ehz-backend-$releaseVersion-linux.zip")
    destinationDirectory.set(releaseDir)
    from(linuxStagingDir)
}

val bundleWindowsRelease by tasks.registering(Zip::class) {
    group = "release"
    description = "Build the Windows air-gap release ZIP."
    dependsOn(generateWindowsSha256Sums)
    archiveFileName.set("ehz-backend-$releaseVersion-windows.zip")
    destinationDirectory.set(releaseDir)
    from(windowsStagingDir)
}

// ---- GitHub release (sibling task, not called by `release`) -----------------

val publishGithubRelease by tasks.registering(Exec::class) {
    group = "release"
    description = "Create a GitHub release with both ZIPs attached (requires `gh` on PATH)."
    dependsOn(bundleLinuxRelease, bundleWindowsRelease)
    val linuxZip = bundleLinuxRelease.get().archiveFile
    val windowsZip = bundleWindowsRelease.get().archiveFile
    inputs.files(linuxZip, windowsZip)
    workingDir = repoRoot.asFile
    commandLine(
        "gh", "release", "create",
        "v$releaseVersion",
        linuxZip.get().asFile.absolutePath,
        windowsZip.get().asFile.absolutePath,
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
    description = "Build images, push (if credentials), and bundle the air-gap ZIPs."
    dependsOn(buildImages, pushImages, bundleLinuxRelease, bundleWindowsRelease)
}
