pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("${rootDir}/vendor/maven")
        }
        maven {
            url = uri("https://npm.ainirobot.com/repository/maven-public/")
            credentials {
                username = "agentMaven"
                password = "agentMaven"
            }
        }
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "NovaPeopleMessenger"
include(":app")
