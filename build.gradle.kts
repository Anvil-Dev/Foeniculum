plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.anvilcraft.foeniculum"
version = providers.gradleProperty("mod_version").get()
base {
    // 发布的构件名与 Maven 坐标的 artifactId 保持一致，避免按 README 写坐标的使用者解析不到插件。
    archivesName = "foeniculum-gradle-plugin"
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
    useJUnitPlatform()
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

publishing {
    // 不手工创建 MavenPublication：java-gradle-plugin 已经提供了 pluginMaven，
    // 它同样发布 java 组件且 GAV 相同，再建一个会让同一坐标被发布两次。
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
