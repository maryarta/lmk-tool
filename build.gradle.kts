plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("ru.hwgrp.lmk.MainKt") }
tasks.test { useJUnitPlatform() }
tasks.jar { archiveClassifier.set("plain") }
tasks.register<Jar>("fatJar") {
    archiveFileName.set("lmk-tool.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

// Pass terminal input through when running the interactive console via Gradle.
tasks.named<JavaExec>("run") { standardInput = System.`in` }
