package com.example.sliwriter

import android.nfc.Tag
import android.nfc.tech.NfcV

class Iso15693Transport(tag: Tag) : AutoCloseable {
    private val nfcv: NfcV = NfcV.get(tag) ?: error("NfcV.get(tag) returned null")

    fun connect() { nfcv.connect() }

    fun setTimeout(ms: Int) {
        // No-op — NfcV timeout not consistently exposed on all Android versions.
    }

    fun transceive(frame: ByteArray): ByteArray = nfcv.transceive(frame)

    fun transceiveChecked(frame: ByteArray): ByteArray {
        val resp = transceive(frame)
        if (resp.isEmpty()) error("Empty response")
        val flags = resp[0].toInt() and 0xFF
        if ((flags and 0x01) != 0) {
            val code = if (resp.size > 1) resp[1].toInt() and 0xFF else -1
            if (code == 0x0F) error("Card error: flags=0x1 code=0xf")
            error("Card error: flags=0x${flags.toString(16)} code=0x${code.toString(16)}")
        }
        return resp
    }

    fun transceiveCheckedWithRetry(
        frame: ByteArray,
        retries: Int,
        retryDelayMs: Long = 20,
        opName: String,
    ): ByteArray {
        var lastErr: Throwable? = null
        repeat(retries) { attempt ->
            try { return transceiveChecked(frame) }
            catch (t: Throwable) {
                lastErr = t
                if (attempt + 1 < retries) Thread.sleep(retryDelayMs)
            }
        }
        throw error("$opName failed after $retries retries: ${lastErr?.message}")
    }

    fun selectCard(uidMsb: ByteArray) {
        val frame = ByteArray(10)
        frame[0] = 0x22
        frame[1] = 0x25
        for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
        transceiveCheckedWithRetry(frame, retries = 3, retryDelayMs = 10, opName = "SELECT")
    }

    fun getSystemInfoAddressed(uidMsb: ByteArray): Pair<Int, Int>? {
        return try {
            val frame = ByteArray(10)
            frame[0] = 0x22
            frame[1] = 0x2B
            for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
            val resp = transceiveChecked(frame)
            if (resp.size < 12) return null
            val infoFlags = resp[1].toInt() and 0xFF
            var offset = 10
            if ((infoFlags and 0x01) != 0) offset++
            if ((infoFlags and 0x02) != 0) offset++
            if ((infoFlags and 0x04) != 0 && resp.size >= offset + 2) {
                val blockCount = (resp[offset].toInt() and 0xFF) + 1
                val blockSize = ((resp[offset + 1].toInt() and 0x1F) + 1).coerceAtMost(4)
                Pair(blockCount, blockSize)
            } else null
        } catch (_: Throwable) { null }
    }

    fun readBlockAddressed(uidMsb: ByteArray, blockNum: Int, blockSize: Int): ByteArray {
        val frame = ByteArray(11)
        frame[0] = 0x22
        frame[1] = 0x20
        for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
        frame[10] = (blockNum and 0xFF).toByte()
        val resp = transceiveCheckedWithRetry(frame, retries = 3, retryDelayMs = 15, opName = "Read block $blockNum")
        require(resp.size >= 1 + blockSize) { "Read block $blockNum: short response ${resp.toHexSpaced()}" }
        return resp.copyOfRange(1, 1 + blockSize)
    }

    fun readBlocksAddressed(
        uidMsb: ByteArray,
        blockCount: Int,
        blockSize: Int,
        onBlock: (Int) -> Unit,
    ): ByteArray {
        val out = ByteArray(blockCount * blockSize)
        for (b in 0 until blockCount) {
            val blk = readBlockAddressed(uidMsb, b, blockSize)
            System.arraycopy(blk, 0, out, b * blockSize, blockSize)
            onBlock(b)
        }
        return out
    }

    /* Non-addressed write (flags=0x02) — normal cards */
    fun writeBlocks(
        data: ByteArray,
        blockCount: Int,
        blockSize: Int,
        onBlock: (Int, ByteArray) -> Unit,
    ) {
        val size = blockSize.coerceIn(1, 4)
        val blocks = blockCount.coerceAtMost(256)
        for (b in 0 until blocks) {
            val frame = ByteArray(7)
            frame[0] = 0x02
            frame[1] = 0x21
            frame[2] = (b and 0xFF).toByte()
            val srcOffset = b * size
            for (i in 0 until 4) {
                frame[3 + i] = if (srcOffset + i < data.size && i < size) data[srcOffset + i] else 0x00
            }
            onBlock(b, frame)
            transceiveCheckedWithRetry(frame, retries = 5, retryDelayMs = 20, opName = "Write block $b")
            Thread.sleep(20)
        }
    }

    /**
     * Addressed + Option write (flags=0x62) — special cards requiring factory UID.
     *
     * Frame: 0x62 | 0x21 | UID[8] LSB-first | block_num | data[4]
     *
     * With Option flag (0x62), NXP cards use a 2-phase response:
     * the card writes to NVM then responds. Android NfcV.transceive() may
     * get no response (tag lost exception) during the NVM write phase.
     * This is normal — we ignore no-response and only fail on explicit error flag.
     */
    fun writeBlocksAddressed(
        uidLsb: ByteArray,   // UID in LSB-first (wire order) = factory UID stored LSB-first
        data: ByteArray,
        blockCount: Int,
        blockSize: Int,
        onBlock: (Int, ByteArray) -> Unit,
    ) {
        val size = blockSize.coerceIn(1, 4)
        val blocks = blockCount.coerceAtMost(256)
        for (b in 0 until blocks) {
            val frame = ByteArray(15)
            frame[0]  = 0x62                        // high rate + addressed + option
            frame[1]  = 0x21                        // WRITE_SINGLE_BLOCK
            for (i in 0 until 8) frame[2 + i] = uidLsb[i]  // already LSB-first
            frame[10] = (b and 0xFF).toByte()
            val srcOffset = b * size
            for (i in 0 until 4) {
                frame[11 + i] = if (srcOffset + i < data.size && i < size) data[srcOffset + i] else 0x00
            }
            onBlock(b, frame)

            // With option flag, no-response (tag lost) is normal during NVM write.
            // Only fail if card explicitly returns an error flag.
            try {
                val resp = transceive(frame)
                if (resp.isNotEmpty() && (resp[0].toInt() and 0x01) != 0) {
                    error("Write block $b: card error ${resp.toHexSpaced()}")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                // Re-throw only on explicit card error, ignore tag-lost/timeout
                if (msg.contains("card error", ignoreCase = true)) throw t
                // else: timeout/no-response = normal with option flag
            }
            Thread.sleep(25)  // NVM write cycle settling
        }
    }

    /* UID write helpers */
    fun buildSetUid40(uidMsb: ByteArray): ByteArray = byteArrayOf(
        0x02, 0xE0.toByte(), 0x09, 0x40,
        uidMsb[7], uidMsb[6], uidMsb[5], uidMsb[4]
    )

    fun buildSetUid41(uidMsb: ByteArray): ByteArray = byteArrayOf(
        0x02, 0xE0.toByte(), 0x09, 0x41,
        uidMsb[3], uidMsb[2], uidMsb[1], uidMsb[0]
    )

    fun writeUid(uidMsb: ByteArray, log: (String) -> Unit) {
        log("SET_UID HIGH: ${uidMsb.slice(0..3).map { "%02X".format(it.toInt() and 0xFF) }.joinToString("")}")
        transceiveCheckedWithRetry(buildSetUid40(uidMsb), 3, 10, "SET_UID_40")
        Thread.sleep(20)
        log("SET_UID LOW:  ${uidMsb.slice(4..7).map { "%02X".format(it.toInt() and 0xFF) }.joinToString("")}")
        transceiveCheckedWithRetry(buildSetUid41(uidMsb), 3, 10, "SET_UID_41")
        Thread.sleep(20)
    }

    fun getRandomNumber(): ByteArray {
        val resp = transceiveChecked(byteArrayOf(0x02, 0xB2.toByte()))
        require(resp.size >= 3) { "GET RANDOM short response: ${resp.toHexSpaced()}" }
        return resp.copyOfRange(1, 3)
    }

    fun setPrivacyPasswordXor(password4: ByteArray, rnd2: ByteArray) {
        require(password4.size == 4)
        require(rnd2.size == 2)
        val xorPwd = byteArrayOf(
            (password4[0].toInt() xor rnd2[0].toInt()).toByte(),
            (password4[1].toInt() xor rnd2[1].toInt()).toByte(),
            (password4[2].toInt() xor rnd2[0].toInt()).toByte(),
            (password4[3].toInt() xor rnd2[1].toInt()).toByte(),
        )
        val frame = byteArrayOf(0x02, 0xB3.toByte(), 0x04, xorPwd[0], xorPwd[1], xorPwd[2], xorPwd[3])
        transceiveChecked(frame)
    }

    override fun close() {
        try { nfcv.close() } catch (_: Throwable) {}
    }
}
