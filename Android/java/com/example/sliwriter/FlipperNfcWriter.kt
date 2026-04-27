package com.example.sliwriter

object FlipperNfcWriter {

    /**
     * Normal read — addressed, using UID from Android tag discovery.
     */
    fun readWithUid(
        transport: Iso15693Transport,
        currentUid: ByteArray,
        log: (String) -> Unit,
    ): FlipperNfcData {
        log("SELECT...")
        transport.selectCard(currentUid)
        Thread.sleep(5)

        val (blockCount, blockSize) = transport.getSystemInfoAddressed(currentUid)
            ?: run {
                log("System info unavailable — using defaults (8 blocks x 4 bytes)")
                Pair(8, 4)
            }
        log("System info: $blockCount blocks x $blockSize bytes")
        log("Reading $blockCount blocks...")

        val data = transport.readBlocksAddressed(currentUid, blockCount, blockSize) { b ->
            log("RDBL ${b.toString().padStart(2, '0')} OK")
        }
        log("Read complete")

        return FlipperNfcData(uid = currentUid, blockCount = blockCount, blockSize = blockSize, data = data)
    }

    /**
     * Special write sequence — mirrors sli_writer.c do_special_write():
     *
     *   Step 1: Restore factory UID (so blocks become writable)
     *   Step 2: Write blocks addressed (flags=0x62) using factory UID in LSB-first order
     *   Step 3: Set target UID from .nfc file
     *
     * @param currentUid    MSB-first UID currently on card (from tag.id.reversedArray())
     * @param factoryUidLsb Factory UID stored LSB-first (as saved by Save Special UID)
     * @param nfcData       Parsed .nfc file content
     */
    fun writeSpecial(
        transport: Iso15693Transport,
        currentUid: ByteArray,      // MSB-first (from tag.id.reversedArray())
        factoryUidLsb: ByteArray,   // LSB-first (stored format)
        nfcData: FlipperNfcData,
        log: (String) -> Unit,
    ) {
        // Factory UID in MSB-first for commands that need it (writeUid, comparison)
        val factoryUidMsb = factoryUidLsb.reversedArray()

        log("Special write: factory=${factoryUidMsb.toHexCompact()} target=${nfcData.uid.toHexCompact()}")

        // Step 1: restore factory UID (skip if card already has factory UID)
        val alreadyFactory = currentUid.contentEquals(factoryUidMsb)
        if (alreadyFactory) {
            log("Step 1: skip (card already has factory UID)")
        } else {
            log("Step 1: restore factory UID")
            transport.writeUid(factoryUidMsb, log)
            Thread.sleep(50)  // card settles after UID change
        }

        // Step 2: write blocks addressed (flags=0x62) with factory UID LSB-first
        log("Step 2: write ${nfcData.blockCount} blocks addressed (0x62)")
        transport.writeBlocksAddressed(
            uidLsb = factoryUidLsb,
            data = nfcData.data,
            blockCount = nfcData.blockCount,
            blockSize = nfcData.blockSize,
        ) { b, frame ->
            log("WRBL ${b.toString().padStart(2, '0')} -> ${frame.toHexSpaced()}")
        }
        log("Blocks written OK")

        // Step 3: set target UID
        val zeroUid = ByteArray(8)
        val uidIsZero = nfcData.uid.contentEquals(zeroUid)
        val uidDiffers = !nfcData.uid.contentEquals(factoryUidMsb)
        if (!uidIsZero && uidDiffers) {
            log("Step 3: write target UID")
            transport.writeUid(nfcData.uid, log)
            log("Target UID OK")
        } else {
            log("Step 3: UID skip (same as factory or zero)")
        }
    }
}
