import org.gradle.api.tasks.JavaExec

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

tasks.register<JavaExec>("protocolCoverageReport") {
    group = "verification"
    description = "Writes the 9.2.2 command contract coverage report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.stzb.server.protocol.CommandCoverageReport")
    args(layout.buildDirectory.file("reports/protocol/command-coverage.md").get().asFile.absolutePath)
}

// 客户端配置表 (tb_cfg_*.bin) 已纳入版本控制, 存放于
// src/main/resources/client-config/, 使构建自包含, 任意机器 clone/pull 后即可运行.
// 更新客户端版本时, 重新从客户端解包目录复制对应 bin 到该目录并提交:
//   ../stzb_9.2.2_out_branch_*/assets/npk_extracted_all/others/res/csharp/data/tcfg[/default]
