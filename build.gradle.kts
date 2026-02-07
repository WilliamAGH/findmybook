import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    java
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "net.findmybook"
version = "0.1.0-SNAPSHOT"

val toolchainJavaVersion = 25
val targetRelease = 25

springBoot {
    mainClass.set("net.findmybook.BookRecommendationEngineApplication")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(toolchainJavaVersion))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(targetRelease)
    options.compilerArgs.add("--enable-preview")
}

val toolchains = project.extensions.getByType(JavaToolchainService::class)

tasks.withType<Test>().configureEach {
    javaLauncher.set(
        toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(toolchainJavaVersion))
        }
    )
    jvmArgs("--enable-preview")
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Dmockito.mock-maker=subclass")
    systemProperty("io.netty.noUnsafe", "true")
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(
        toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(toolchainJavaVersion))
        }
    )
    jvmArgs("--enable-preview")
    systemProperty("io.netty.noUnsafe", "true")
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.spring.io/milestone")
    }
}

extra["springAiVersion"] = "2.0.0-M2"
extra["testcontainersVersion"] = "2.0.3"
extra["resilience4jVersion"] = "2.3.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    implementation("org.postgresql:postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation(platform("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    implementation("commons-io:commons-io:2.18.0")
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("io.github.wimdeblauwe:htmx-spring-boot-thymeleaf:5.0.0")
    implementation("software.amazon.awssdk:s3:2.29.35")
    implementation("me.paulschwarz:spring-dotenv:5.1.0")
    implementation("org.springframework.retry:spring-retry:2.0.12")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:${property("resilience4jVersion")}")
    implementation("io.github.resilience4j:resilience4j-bulkhead:${property("resilience4jVersion")}")
    implementation("io.github.resilience4j:resilience4j-micrometer:${property("resilience4jVersion")}")

    implementation("com.google.errorprone:error_prone_annotations:2.36.0")

    compileOnly("org.projectlombok:lombok:1.18.40")
    annotationProcessor("org.projectlombok:lombok:1.18.40")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-cache-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-jdbc-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-localstack")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    enabled = false
}

val skipFrontend = project.hasProperty("skipFrontend")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val frontendInstall by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine(npmExecutable, "install")
    enabled = !skipFrontend
}

val frontendCheck by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine(npmExecutable, "run", "check")
    dependsOn(frontendInstall)
    enabled = !skipFrontend
}

val frontendTest by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine(npmExecutable, "run", "test")
    dependsOn(frontendInstall)
    enabled = !skipFrontend
}

val frontendBuild by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine(npmExecutable, "run", "build")
    dependsOn(frontendInstall)
    enabled = !skipFrontend
}

tasks.named("processResources") {
    dependsOn(frontendBuild)
}

tasks.named("check") {
    dependsOn(frontendCheck, frontendTest)
}
