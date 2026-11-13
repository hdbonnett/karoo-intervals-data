pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext is published on GitHub Packages, which always requires a token
        // (classic personal access token with the read:packages scope).
        // Set gpr.user / gpr.key in ~/.gradle/gradle.properties, or GPR_USER / GPR_KEY
        // environment variables (the CI workflow uses the latter).
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GPR_KEY")
            }
            content { includeGroup("io.hammerhead") }
        }
        // Allows building against a locally published karoo-ext (gradlew lib:publishToMavenLocal in a karoo-ext clone).
        mavenLocal {
            content { includeGroup("io.hammerhead") }
        }
    }
}

rootProject.name = "karoo-intervals-data"
include(":core", ":app")
