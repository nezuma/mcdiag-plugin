plugins {
    java
}

group = "net.mcdiag"
version = "0.9.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API и то, что ядро уже несёт с собой. Никаких зависимостей внутри jar.
    compileOnly("io.papermc.paper:paper-api:1.19.4-R0.1-SNAPSHOT")
    // Appender для перехвата всего лога: Log4j поставляется вместе с ядром (Paper 1.19.4 — 2.19.0).
    compileOnly("org.apache.logging.log4j:log4j-core:2.19.0")
    // Необязательная интеграция: классы LuckPerms загружаются, только если плагин установлен.
    compileOnly("net.luckperms:api:5.4")
    // Публичный API для других плагинов: его классы лежат в jar агента.
    compileOnly(project(":api"))

    testImplementation("io.papermc.paper:paper-api:1.19.4-R0.1-SNAPSHOT")
    testImplementation("org.apache.logging.log4j:log4j-core:2.19.0")
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-deprecation", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    from(project(":api").sourceSets.main.get().output)
    // Имя без версии: панель ссылается на releases/latest/download/MCDiag-Agent.jar.
    archiveFileName.set("MCDiag-Agent.jar")
}
