plugins {
    id("qupath-conventions")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-dialog-manager"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "A QuPath extension for managing dialog window positions with persistence and recovery."
    automaticModule = "io.github.qupath.extension.dialogmanager"
}

dependencies {
    // Main dependencies for QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.gson)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation(libs.bundles.logging)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}
