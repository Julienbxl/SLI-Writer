package com.example.sliwriter

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

fun InputStream.readAllText(): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

fun ByteArray.toHexSpaced(): String =
    joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

fun ByteArray.toHexCompact(): String =
    joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
