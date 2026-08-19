import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.9.0"
    id("jacoco")
}

group = "com.passagevpn"
version = "0.1.0-SNAPSHOT"

description = "OpenVPN management panel"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

jacoco {
    toolVersion = "0.8.15"
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16")
    }
}

dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database: SQLite + Flyway (PostgreSQL-ready)
    implementation("org.xerial:sqlite-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    // Flyway 9.x: last line with built-in SQLite support; also works for PostgreSQL.
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation("org.hibernate.orm:hibernate-community-dialects")

    // Security / auth
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("dev.samstevens.totp:totp:1.7.1")
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")

    // API documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // Utilities
    implementation("org.apache.commons:commons-lang3")
    implementation("commons-io:commons-io:2.18.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("passed", "skipped", "failed")
    }
    // Hermetic tests: use a temp dir for SQLite files
    environment("PASSAGE_DB_URL", "jdbc:sqlite:${layout.buildDirectory.get().asFile.resolve("testdb.sqlite")}")
    environment("PASSAGE_JWT_SECRET", "test-secret-that-is-at-least-32-bytes-long-for-jwt")
    environment("PASSAGE_INTERNAL_TOKEN", "test-internal-token-for-unit-tests-only")
    environment("PASSAGE_OPENVPN_MGMT_PASSWORD", "test-mgmt-password")
    // A fresh DB each run avoids Flyway checksum drift across builds.
    doFirst {
        fileTree(layout.buildDirectory.get().asFile).matching { include("testdb.sqlite*") }.forEach { it.delete() }
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.28.0")
        removeUnusedImports()
    }
}
