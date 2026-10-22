plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.anvilcraft.foeniculum"
version = providers.gradleProperty("mod_version").get()
base {
    archivesName = "foeniculum"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

val functionalTest: SourceSet = sourceSets.create("functionalTest")

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    implementation(libs.gson)
    implementation(libs.maven.artifact)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    "functionalTestImplementation"(platform(libs.junit.bom))
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("foeniculum") {
            id = "dev.anvilcraft.foeniculum"
            implementationClass = "dev.anvilcraft.foeniculum.FoeniculumPlugin"
            displayName = "Foeniculum"
            description = "Slims and flattens ModDevGradle jar-in-jar dependencies across a multi-module dependency tree."
        }
    }
    testSourceSets(functionalTest)
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs functional tests against the plugin using the Gradle TestKit."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform {
        excludeTags("neoforge")
    }
}

tasks.register<Test>("neoforgeIntegrationTest") {
    description = "Runs functional tests that require downloading a real NeoForge version."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform {
        includeTags("neoforge")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(functionalTestTask)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    systemProperty("foeniculum.version", project.version.toString())
    // The functional tests resolve their fixtures from a temporary local repository.
    systemProperty("foeniculum.testkit.repo", layout.buildDirectory.dir("testkit-repo").get().asFile.absolutePath)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        val mavenUrl = System.getenv("MAVEN_URL")
        if (mavenUrl != null) {
            maven {
                url = uri(mavenUrl)
                credentials {
                    username = System.getenv("MAVEN_USERNAME")
                    password = System.getenv("MAVEN_PASSWORD")
                }
            }
        } else {
            mavenLocal()
        }
    }
}
