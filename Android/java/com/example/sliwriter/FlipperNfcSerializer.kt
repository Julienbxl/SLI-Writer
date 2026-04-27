package com.example.sliwriter

object FlipperNfcSerializer {
    fun serialize(uid: ByteArray, data: ByteArray, blockCount: Int = 8, blockSize: Int = 4): String {
        require(uid.size == 8) { "UID must be 8 bytes" }
        require(blockCount in 1..256) { "Block Count must be 1..256" }
        require(blockSize in 1..0x20) { "Block Size must be 1..0x20" }

        val uidStr = uid.toHexSpaced()
        val dataStr = data.toHexSpaced()
        val security = List(blockCount) { "00" }.joinToString(" ")

        return buildString {
            appendLine("Filetype: Flipper NFC device")
            appendLine("Version: 4")
            appendLine("# Device type can be ISO14443-3A, ISO14443-3B, ISO14443-4A, ISO14443-4B, ISO15693-3, FeliCa, NTAG/Ultralight, Mifare Classic, Mifare Plus, Mifare DESFire, SLIX, ST25TB, NTAG4xx, Type 4 Tag, EMV")
            appendLine("Device type: SLIX")
            appendLine("# UID is common for all formats")
            appendLine("UID: $uidStr")
            appendLine("# ISO15693-3 specific data")
            appendLine("# Data Storage Format Identifier")
            appendLine("DSFID: 00")
            appendLine("# Application Family Identifier")
            appendLine("AFI: 00")
            appendLine("# IC Reference - Vendor specific meaning")
            appendLine("IC Reference: 03")
            appendLine("# Lock Bits")
            appendLine("Lock DSFID: false")
            appendLine("Lock AFI: false")
            appendLine("# Number of memory blocks, valid range = 1..256")
            appendLine("Block Count: $blockCount")
            appendLine("# Size of a single memory block, valid range = 01...20 (hex)")
            appendLine("Block Size: %02X".format(blockSize))
            appendLine("Data Content: $dataStr")
            appendLine("# Block Security Status: 01 = locked, 00 = not locked")
            appendLine("Security Status: $security")
            appendLine("# SLIX specific data")
            appendLine("# SLIX capabilities field affects emulation modes. Possible options: Default, AcceptAllPasswords")
            appendLine("Capabilities: Default")
            appendLine("# Passwords are optional. If a password is omitted, a default value will be used")
            appendLine("Password Privacy: 7F FD 6E 5B")
            appendLine("Password Destroy: 0F 0F 0F 0F")
            appendLine("Password EAS: 00 00 00 00")
            appendLine("Privacy Mode: false")
            appendLine("# SLIX Lock Bits")
            appendLine("Lock EAS: false")
        }
    }
}
