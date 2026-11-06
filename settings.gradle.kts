pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Xposed api 82 原本只在 jcenter；用阿里云镜像兜底
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://api.xposed.info/") }
    }
}
rootProject.name = "U闲"
include(":app")
