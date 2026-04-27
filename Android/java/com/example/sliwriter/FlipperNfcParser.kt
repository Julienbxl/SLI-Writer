package com.example.sliwriter

data class FlipperNfcData(
    val uid: ByteArray,
    val blockCount: Int,
    val blockSize: Int,
    val data: ByteArray,
)

object FlipperNfcParser {
    fun parse(text: String): FlipperNfcData {
        val uid = ByteArray(8)
        var blockCount = 0
        var blockSize = 4
        var data = ByteArray(0)

        findField(text, "UID: ")?.let { uidField ->
            val parsedUid = parseFixedHexBytes(uidField, 8)
            System.arraycopy(parsedUid, 0, uid, 0, 8)
        } ?: error("UID field not found")

        findField(text, "Block Count: ")?.let {
            blockCount = it.trim().toInt()
        } ?: error("Block Count field not found")

        findField(text, "Block Size: ")?.let {
            blockSize = it.trim().toInt(16).takeIf { v -> v > 0 } ?: 4
        } ?: error("Block Size field not found")

        findField(text, "Data Content: ")?.let { dataField ->
            val total = (blockCount * blockSize).coerceAtLeast(0)
            val raw = parseFlatHexBytes(dataField)
            data = if (raw.size >= total) raw.copyOfRange(0, total) else raw + ByteArray(total - raw.size)
        } ?: error("Data Content field not found")

        return FlipperNfcData(uid, blockCount, blockSize, data)
    }

    private fun findField(text: String, fieldName: String): String? {
        val idx = text.indexOf(fieldName)
        if (idx < 0) return null
        val start = idx + fieldName.length
        val next = text.indexOf('\n', start)
        return if (next >= 0) text.substring(start, next).trim() else text.substring(start).trim()
    }

    private fun parseFixedHexBytes(s: String, count: Int): ByteArray {
        val tokens = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        require(tokens.size >= count) { "Expected $count UID bytes, got ${tokens.size}" }
        return ByteArray(count) { i -> tokens[i].toInt(16).toByte() }
    }

    private fun parseFlatHexBytes(s: String): ByteArray {
        val tokens = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return ByteArray(tokens.size) { i -> tokens[i].toInt(16).toByte() }
    }
}
