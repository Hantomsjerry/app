package com.example.myapp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildAbiContractTest {
    @Test
    fun defaultsToArm64AndAllowsExplicitX86EmulatorBuilds() {
        val source = File("build.gradle.kts").readText()

        assertTrue(source.contains("x86EmulatorBuild"))
        assertTrue(source.contains("providers.gradleProperty(\"x86EmulatorBuild\")"))
        assertTrue(source.contains("if (x86EmulatorBuild) \"x86_64\" else \"arm64-v8a\""))
    }
}
