plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-dialog-manager"
    group = "io.github.qupath"
    version = "0.3.1"
    description = "A QuPath extension for managing dialog window positions with persistence and recovery."
    automaticModule = "io.github.qupath.extension.dialogmanager"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Main dependencies for QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.gson)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation(libs.bundles.logging)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}
