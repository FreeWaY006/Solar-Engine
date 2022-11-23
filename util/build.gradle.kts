plugins {
    kotlin("jvm")
}

dependencies {
    val asmVersion = "9.4"
    api("org.ow2.asm:asm:$asmVersion")
    api("org.ow2.asm:asm-commons:$asmVersion")
    api("org.ow2.asm:asm-util:$asmVersion")
    api("org.jetbrains.kotlin:kotlin-reflect:1.7.20")
}