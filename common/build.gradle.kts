plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.exposed.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.h2)
    implementation(libs.typesafe.config)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}