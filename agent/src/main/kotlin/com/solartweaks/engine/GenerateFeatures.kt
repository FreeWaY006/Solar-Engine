@file:JvmName("GenerateFeatures")

package com.solartweaks.engine

import java.io.File

// Generates Features.md
fun main() {
    val modulesText = schemaOfModules().modules.values
        .sortedBy { it.displayName }
        .joinToString("\n") { def ->
            val propsText = def.options.values.joinToString("\n") {
                "  - ${it.displayName}: ${it.description}"
            }

            """
            - ${def.displayName}: ${def.description}
            
            """.trimIndent() + propsText
        }

    File("Features.md").writeText(
        """
        # Features
        This is an auto-generated list of all the features that Solar Engine (v$version) currently supports.
        For every module, every option/property is listed as well.
        
        """.trimIndent() + modulesText
    )
}