import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

project.version = "2.0"
plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.41"

    application
    id("com.github.johnrengelman.shadow") version("5.1.0")
    id ("com.bmuschko.docker-remote-api") version("5.2.0")
    id("com.bmuschko.docker-java-application") version "5.2.0"

}

repositories {
    jcenter()
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.3.1")

    implementation("dev.misfitlabs.kotlinguice4:kotlin-guice:1.4.1")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.0")
    implementation( "net.dean.jraw:JRAW:1.1.0")
    implementation( "org.postgresql:postgresql:42.2.8")
    implementation( "com.google.code.gson:gson:2.8.5")
    implementation(group= "org.slf4j", name= "slf4j-api", version= "1.7.28")
    implementation(group= "org.slf4j", name= "slf4j-jdk14", version= "1.7.28")

    implementation(group="com.google.inject", name= "guice", version = "4.2.3")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
    testImplementation( "org.mockito:mockito-inline:2.13.0")



    implementation("io.github.microutils:kotlin-logging:1.5.9")
    implementation("com.uchuhimo:konf-core:0.20.0")
    implementation("com.uchuhimo:konf-yaml:0.20.0")
    implementation("org.yaml:snakeyaml:1.25")
}

application {
    mainClassName = "de.rtrx.a.MainKt"
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

docker {

    registryCredentials {
        val GITHUB_USER: String by project
        val GITHUB_TOKEN: String by project

        username.set(GITHUB_USER)
        password.set(GITHUB_TOKEN)
        url.set("docker.pkg.github.com")
    }

    javaApplication {
        ports.set(listOf<Int>())
        baseImage.set("openjdk:8")
        maintainer.set("Artraxon a@rtrx.de")
        tag.set("docker.pkg.github.com/artraxon/unexbot/full-image:${project.version}")
    }
}
