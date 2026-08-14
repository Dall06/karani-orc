
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
}

group = "com.dall06.karani"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.websockets)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(libs.logback.classic)

    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation("org.apache.kafka:kafka-clients:3.7.0")
    
    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.53.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.53.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.53.0")
    
    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    
    // Drivers
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.1.0")

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.mock)
}
