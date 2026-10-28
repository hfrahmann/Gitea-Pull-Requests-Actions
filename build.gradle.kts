import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2026.1 (sinceBuild 261) bundles kotlin-stdlib 2.3.x – don't call newer stdlib APIs
        apiVersion = KotlinVersion.KOTLIN_2_3
        // Rely on the platform's JVM default methods instead of generating bridges to them – those bridges
        // count as overrides of deprecated defaults (e.g. VcsCloneDialogExtension.createMainComponent(Project))
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        // No warnings – deliberate exceptions get a local @Suppress with a reason
        allWarningsAsErrors = true
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.platform.launcher)
    detektPlugins(libs.detekt.ktlint)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.tasks")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledModule("intellij.platform.collaborationTools")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Plugin description = the <!-- Plugin description --> section of README.md
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Released IDEs from sinceBuild on – no EAP builds. IntelliJ IDEA is one product since 2025.3 (no
            // separate Community builds for 261+); PyCharm as a second IDE without Java support.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea, IntelliJPlatformType.PyCharm)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("pluginSinceBuild")
            }
        }
        // Fail on anything that breaks the plugin or uses non-public API; deprecated and experimental API is allowed
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
}

intellijPlatformTesting {
    // Integration tests need the test Gitea from .github/scripts/ci-gitea.sh (see README.md): ./gradlew integrationTest
    testIde {
        register("integrationTest") {
            task {
                description = "Runs tests tagged 'integration' against the local dev Gitea."
                group = "verification"
                useJUnitPlatform { includeTags("integration") }
                // CI points the tests at a fresh Gitea without an Actions runner
                systemProperty("gitea.test.env", providers.environmentVariable("GITEA_TEST_ENV").getOrElse(""))
                systemProperty("gitea.test.no.runner", providers.environmentVariable("GITEA_TEST_NO_RUNNER").getOrElse(""))
                // This task has no IntelliJ test framework on its classpath – run only the plain JUnit 5 integration tests
                isScanForTestClasses = false
                include("**/*IntegrationTest.class")
            }
        }
    }
}

// Static analysis, part of `check`; real findings are fixed or listed in the baseline
detekt {
    buildUponDefaultConfig = true
    config.setFrom(layout.projectDirectory.file("detekt.yml"))
    baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
    parallel = true
}

// Coverage of the unit tests (integrationTest needs a Gitea and stays out). Reports cover everything; the
// "core" variant (API client, DTOs, data/mapping) has minimums – the measured values of 2026-10-24, only ever raise them.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("integrationTest")
        }
        createVariant("core") {
            add("jvm")
        }
    }
    reports {
        variant("core") {
            filters {
                includes {
                    packages(
                        "de.hendrikfrahmann.giteaplugin.api",
                        "de.hendrikfrahmann.giteaplugin.api.dto",
                        "de.hendrikfrahmann.giteaplugin.api.request",
                        "de.hendrikfrahmann.giteaplugin.actions.data",
                        "de.hendrikfrahmann.giteaplugin.pullrequest.data",
                        "de.hendrikfrahmann.giteaplugin.authentication.accounts",
                        "de.hendrikfrahmann.giteaplugin.repository",
                    )
                }
                // `packages` includes subpackages; the repository/account selector is UI
                excludes {
                    packages("de.hendrikfrahmann.giteaplugin.repository.ui")
                }
            }
            verify {
                rule("core line coverage") {
                    minBound(73)
                }
                rule("line coverage of each core package") {
                    groupBy = GroupingEntityType.PACKAGE
                    minBound(46)
                }
            }
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/hfrahmann/Gitea-Pull-Requests-Actions"
}

tasks {
    // Kover 0.9.10 records the test tasks' outcomes in a listener the configuration cache bypasses: with the cache on, the
    // coverage of `test` is dropped from the report (0 %), so `koverVerifyCore` – and with it `check`/`build` – failed.
    // Builds that run a Kover task go without the configuration cache.
    matching { it.name.startsWith("kover") }.configureEach {
        notCompatibleWithConfigurationCache("Kover drops the test coverage when the configuration cache is reused")
    }

    check {
        dependsOn("koverVerifyCore", "verifyPluginProjectConfiguration", "verifyPluginStructure")
    }

    test {
        // The IU test runtime also contains "Git Integration for JetBrains Client", whose content module clashes with
        // Git4Idea's, so Git4Idea – and with it this plugin – would not load. Load only what we need.
        // "Issue Trackers" for the optional Tasks integration.
        systemProperty("idea.load.plugins.id", "de.hendrikfrahmann.giteaplugin,Git4Idea,com.intellij.tasks,org.jetbrains.plugins.yaml")
        // Leaked disposables report where they were created. Slow operations on the EDT are forbidden per test
        // (forbidSlowOperations in TestUtil.kt) – globally the platform's own test setup trips over it.
        systemProperty("idea.disposer.debug", "on")
        useJUnitPlatform {
            // Integration tests need the test Gitea from .github/scripts/ci-gitea.sh (see README.md): ./gradlew integrationTest
            excludeTags("integration")
        }
    }

    wrapper {
        gradleVersion = "9.8.1"
    }
}
