plugins { id("org.jetbrains.kotlin.jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies { implementation(project(":core")) }
application { mainClass.set("net.jamesjennison.filamajignfc.catalog.CatalogToolKt") }
