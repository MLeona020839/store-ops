// Shared event contracts. Kept free of Spring so both services
// (and any future consumer) can depend on it without pulling in a framework.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}
