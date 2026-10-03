plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

room { schemaDirectory("$projectDir/schemas") }

// The local vision service address is private to each builder. It comes from the OLLAMA_BASE_URL environment
// variable or the untracked .env.local file; a build without either has no local vision fallback.
fun ollamaBaseUrl(): String {
    val fromEnvironment = providers.environmentVariable("OLLAMA_BASE_URL").orNull
    val fromFile = rootProject.file(".env.local").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("OLLAMA_BASE_URL=") }?.substringAfter("=")
    val value = (fromEnvironment ?: fromFile).orEmpty().trim()
    require(value.none { it == '"' || it == '\\' || it == '$' }) { "OLLAMA_BASE_URL contains unsupported characters" }
    return value
}

android {
    namespace = "net.jamesjennison.filamajignfc"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        // The installed app identity. The namespace and Kotlin packages keep their original name.
        applicationId = "net.jamesjennison.spoolforge"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0-m8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OLLAMA_BASE_URL", "\"${ollamaBaseUrl()}\"")
        buildConfigField("String", "OLLAMA_VISION_MODEL", "\"qwen3.5:9b-q8_0\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("test").resources.srcDir("schemas")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.robolectric:robolectric:4.16.1")
}

val generatedCatalog = layout.projectDirectory.file("src/main/assets/ofd-catalog.tsv.gzip")
tasks.register<JavaExec>("generateCatalog") {
    dependsOn(":catalog-tool:classes")
    classpath = project(":catalog-tool").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("net.jamesjennison.filamajignfc.catalog.CatalogToolKt")
    args(rootProject.file("research/sources/ofd/data"), generatedCatalog.asFile, rootProject.file("research/sources/ofd/LICENSE"))
}
tasks.named("preBuild") { dependsOn("generateCatalog") }
