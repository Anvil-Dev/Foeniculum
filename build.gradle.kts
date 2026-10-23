plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.jreleaser)
}

group = "dev.anvilcraft.resource"
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
            id = "dev.anvilcraft.resource.foeniculum"
            implementationClass = "dev.anvilcraft.resource.foeniculum.FoeniculumPlugin"
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

// Maven Central 要求同时发布 javadoc 与 sources。前者 java-gradle-plugin 不会自动提供，需要显式声明。
val javadocJar = tasks.register<Jar>("javadocJar") {
    description = "Assembles a jar archive containing the generated Javadoc."
    group = "build"
    archiveClassifier = "javadoc"
    from(tasks.named<Javadoc>("javadoc"))
}

// javadoc 只用于发布，不让它的告警阻断构建；真正的编码问题由编译与测试负责。
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
        charSet = "UTF-8"
    }
}

publishing {
    // 不手工创建 MavenPublication：java-gradle-plugin 已经提供了 pluginMaven，
    // 它同样发布 java 组件且 GAV 相同，再建一个会让同一坐标被发布两次。
    publications.withType<MavenPublication>().configureEach {
        // 插件标记发布是只有 POM 的坐标标记，给它挂 javadoc 会在标记坐标下多出一个孤立构件。
        if (!name.endsWith("PluginMarkerMaven")) {
            artifact(javadocJar)
        }
        pom {
            name = "Foeniculum"
            description =
                "A ModDevGradle companion that slims and flattens jar-in-jar dependencies across a multi-module tree."
            url = "https://github.com/Anvil-Dev/Foeniculum"
            licenses {
                license {
                    name = "GNU Lesser General Public License 3.0"
                    url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                }
            }
            developers {
                developer {
                    id = "Anvil-Dev"
                    name = "AnvilCraft Dev"
                    email = "admin@anvilcraft.dev"
                }
            }
            scm {
                url = "https://github.com/Anvil-Dev/Foeniculum"
                connection = "https://github.com/Anvil-Dev/Foeniculum.git"
                developerConnection = "https://github.com/Anvil-Dev/Foeniculum.git"
            }
        }
    }

    repositories {
        // 私有 Maven：有凭据时发布到它，否则退回本地仓库，便于开发者验证发布内容。
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
        // Maven Central 不直接接收上传：先把产物放进本地 staging 目录，再由 jreleaser 校验并上传递交。
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

jreleaser {
    signing {
        active = org.jreleaser.model.Active.ALWAYS
        armored = true
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active = org.jreleaser.model.Active.ALWAYS
                    url = "https://central.sonatype.com/api/v1/publisher"
                    stagingRepository(
                        layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath,
                    )
                }
            }
        }
    }
}
