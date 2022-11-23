@file:JvmName("GenerateConfigurations")

package com.solartweaks.engine

import kotlinx.serialization.encodeToString
import java.io.File

// Generates config.example.json and metadata.json
fun main() {
    File("config.example.json").writeText(json.encodeToString(Configuration()))
    File("metadata.json").writeText(json.encodeToString(schemaOfModules()))
}