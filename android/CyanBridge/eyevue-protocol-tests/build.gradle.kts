plugins {
    kotlin("jvm") version "2.3.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include("com/fersaiyan/cyanbridge/devices/eyevue/EyevueProtocol.kt")
        }
    }
    test {
        kotlin {
            srcDir("../app/src/test/java")
            include("com/fersaiyan/cyanbridge/devices/eyevue/EyevueProtocolTest.kt")
        }
    }
}

tasks.test {
    useJUnit()
}
