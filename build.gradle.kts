plugins { kotlin("jvm") }

dependencies {
    compileOnly(project(":devline-ide-extensions"))
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(project(":devline-ide-extensions"))
    implementation("org.gradle:gradle-tooling-api:9.7.1")
}
tasks.processResources { from("src/extension/extension.json") { rename { "gradle-extension.json" } } }
val panelBuild = tasks.register<Exec>("panelBuild") {
    workingDir(rootProject.projectDir)
    commandLine("node", "node_modules/vite/bin/vite.js", "build", "--config", project.file("web/vite.config.ts").absolutePath, "--outDir", layout.buildDirectory.dir("panel").get().asFile.absolutePath)
    inputs.dir("web"); inputs.dir(rootProject.file("devline-ui-web/src"))
    outputs.dir(layout.buildDirectory.dir("panel"))
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild)
    workingDir(rootProject.projectDir)
    commandLine("node", project.file("web/pack.mjs").absolutePath, layout.buildDirectory.dir("panel").get().asFile.absolutePath, layout.buildDirectory.dir("extension-web").get().asFile.absolutePath)
    inputs.dir(layout.buildDirectory.dir("panel")); outputs.dir(layout.buildDirectory.dir("extension-web"))
}
val extensionArchive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    archiveFileName.set("devline-gradle-0.1.1.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from("src/extension/extension.json")
    from(packPanel) { into("web") }
    into("lib") { from(tasks.jar); from(configurations.runtimeClasspath) { exclude("kotlin-stdlib-*.jar", "annotations-*.jar", "error_prone_annotations-*.jar") } }
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
}
configurations.create("bundledExtension") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts.add("bundledExtension", extensionArchive)
tasks.register<JavaExec>("gradleCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.gradle.GradleExtensionCheckKt")
    args(layout.buildDirectory.dir("checks").get().asFile.absolutePath)
    providers.gradleProperty("gradleFixtureInstallation").orNull?.let { args(it) }
}
