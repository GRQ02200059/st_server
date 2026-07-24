plugins {
    kotlin("jvm") version "1.9.23"
    application
}

group = "com.stzb"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.109.Final")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.stzb.server.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
