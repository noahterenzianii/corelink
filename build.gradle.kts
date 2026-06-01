plugins {
    id("net.fabricmc.fabric-loom") version "1.16.2"
    id("maven-publish")
}

base {
    archivesName = project.property("archives_base_name") as String
}

version = "${project.property("mod_version")}+mc${project.property("minecraft_version")}"
group = project.property("maven_group") as String

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

repositories {
    mavenCentral()
}

val mcVersion = project.property("minecraft_version") as String
val loaderVersion = project.property("loader_version") as String
val fabricApiVersion = project.property("fabric_api_version") as String

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    include(implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")!!)
    include(implementation("org.xerial:sqlite-jdbc:3.45.3.0")!!)
}

tasks.processResources {
    val expansionProps = mapOf(
        "version" to project.version,
        "minecraft_version" to mcVersion,
        "loader_version" to loaderVersion
    )
    inputs.properties(expansionProps)
    filesMatching("fabric.mod.json") {
        expand(expansionProps)
    }
}
