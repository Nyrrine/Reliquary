plugins {
    java
}

group = "com.nyrrine"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.1.2 API (Mojang-mapped). compileOnly: the server provides it at runtime.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // Headless unit tests for the extraction chemistry. The engine math is server-free, but the model
    // enums carry Adventure colours, so the API is needed on the test classpath (it isn't at runtime).
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("Reliquary")
}
