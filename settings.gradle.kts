pluginManagement {
    repositories {
        // dl.google.com / repo1.maven.org time out here; the Aliyun mirrors proxy both.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "xiaoman"

include(
    ":core-api",
    ":core-llm",
    ":core-memory",
    ":core-skills",
    ":core-sentinel",
    ":core-agent",
    ":hands",
    ":app",
)
