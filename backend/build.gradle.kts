import java.io.IOException
import java.util.concurrent.TimeUnit

plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tabletophelper"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Integration tier (D172): real PostgreSQL through Testcontainers, run with ./gradlew integrationTest.
// Not wired into check, so test, build and the Dockerfile's bootJar never need Docker.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(dependencyManagement.importedProperties.getValue("junit-jupiter.version"))
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.springframework.security:spring-security-test")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:postgresql")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(tasks.test)
                        // Testcontainers 1.20 asks for Docker API 1.32, which Docker Engine 29 and
                        // current Docker Desktop reject; 1.44 needs Docker 25 or later
                        systemProperty("api.version", "1.44")
                        // Never skipped, not even as up to date: a green run always means the tier ran,
                        // and without Docker it fails at once
                        outputs.upToDateWhen { false }
                        doFirst {
                            val dockerRunning = try {
                                val docker = ProcessBuilder("docker", "info")
                                    .redirectErrorStream(true)
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .start()
                                val finished = docker.waitFor(30, TimeUnit.SECONDS)
                                if (!finished) docker.destroyForcibly()
                                finished && docker.exitValue() == 0
                            } catch (e: IOException) {
                                false
                            }
                            if (!dockerRunning) {
                                throw GradleException("Docker isn't running: start Docker Desktop and rerun.")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Integration tests compile against the same libraries as the application code
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}
