plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName = "corelink-client"
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

// The client mod is a thin companion of the CoreLink server mod: it only
// registers a keybinding, so Fabric API is a declared runtime dependency
// (every client that plays on a CoreLink server already has it).
dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
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
