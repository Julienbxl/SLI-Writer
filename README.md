I made a simple Flipper app to write magic ISO15693 changeable UID with .nfc file 
EDIT 26/03 I developped the Android version of the app, available as .apk, I can share sources if anyone needs them.

For a SLIX2/ISO15693 use : aliexpress.com/item/1005006949791537.html?
One user reporter the round ones aren't working, so stick with the rectangulars !

For SLIX-L tags use : http://rfidfriend.com 
  
I tried to vibecode the app 6 months ago but I was stuck, somehow it ended in Roguemaster firmawre but I don't know if it was corrected ... ?   

I recently found out the "ISO 15693-3 NFC Writer" app in Unleashed and it had everything needed for me to correct the code - so now it is working fine.

## Write sequence

1. Write all data blocks (non-addressed `WRITE_SINGLE_BLOCK`, flags `0x02`)
2. Write UID using Gen2 vendor commands:
   - `02 E0 09 40 <uid_high>` — sets bytes 0–3
   - `02 E0 09 41 <uid_low>` — sets bytes 4–7
   - equivalent to Proxmark `hf 15 csetuid -u <uid> --v2`

> **Note:** The Gen2 layout command (`0x47`) is intentionally **not sent**.  
> It is not needed for most readers, and would brick SLIX-L magic cards.

## Debugging

If the write fails, connect your Flipper via USB and open a serial console (Putty, `screen`, etc.) at **230400 baud**.

Enable debug logs on the Flipper:
```
> log debug
```

Then look for `[SLI_Writer]` lines. Key messages:
- `iso_send_raw: err=0 rxbytes=2 resp=[XX YY]` — card received the command but returned an error; `YY` is the ISO15693 error code
- `iso_send_raw: err=6 rxbytes=0` — card did not respond (timeout/CRC issue)
- `Write block N failed` — block write failed after 5 retries

---

## Changelog

### v2 — Debug improvements
- Added response byte logging: `resp=[XX YY]` now shows the exact ISO15693 error code returned by the card
- This helps diagnose cards that receive commands but refuse to write

### v1.1 — Timing improvements
- FWT (frame wait time): `300000` → `500000` carrier cycles (~37ms) — more time for card response
- Inter-block delay: `12ms` → `20ms` — more time for NVM write cycle
- Write retries: `3` → `5` attempts with `20ms` between each
- Improves compatibility with some card variants (round vs rectangular form factor)

### v1.0 — Initial release
- Parse `.nfc` file (UID, block count, block size, data)
- Write data blocks using non-addressed `WRITE_SINGLE_BLOCK`
- Write UID using Gen2 vendor commands (`0x40` / `0x41`)
- Callback-driven NFC poller (no custom worker thread — stable, no crashes)
- LED feedback: blue on scan, green on success, red on error
- Based on the official `iso15693_nfc_writer` worker pattern from Unleashed
