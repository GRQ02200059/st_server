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

tasks.processResources {
    from(
        "../stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg",
    ) {
        include(
            "tb_cfg_army.bin",
            "tb_cfg_army_count.bin",
            "tb_cfg_hero_u.bin",
        )
        into("client-config")
    }
    from(
        "../stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg/default",
    ) {
        include(
            "tb_cfg_card_extract*.bin",
            "tb_cfg_gear.bin",
            "tb_cfg_gear_feature.bin",
            "tb_cfg_hero_type_feature.bin",
        )
        into("client-config")
    }
    from(
        "../stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg",
    ) {
        include("tb_cfg_gear_u.bin")
        into("client-config")
    }
    from(
        "../stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg",
    ) {
        include("tb_cfg_card_prob*.bin")
        into("client-config")
    }
}
