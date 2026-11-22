# EV3 Scan

Android-app som läser batteridata från en **Kia EV3** via en BLE ELM327-adapter
(testad mot UniCarScan **UCSI-2100**).

Visar: SoC (display + BMS), SoH, kvarvarande energi, effekt (kW), spänning, ström,
max laddeffekt, batteritemperaturer, högsta/lägsta cell, alla cellspänningar,
12V-batteri, livstidsenergi. Logg-fliken har rålogg och manuell kommandoinmatning.

## Installera
Ladda ner senaste `EV3Scan-*.apk` under **Releases** och installera
(tillåt "installera okända appar" för webbläsaren/filhanteraren).

## Bygga
GitHub Actions bygger automatiskt vid varje push till `main`.

## Källor
Byte-positioner för BMS (7E4 → 7EC, DID 0101/0105/0102–010C) kommer från
[OBDb/Kia-EV3](https://github.com/OBDb/Kia-EV3) (CC BY-SA 4.0) och är
verifierade mot deras inspelade EV3-svar i enhetstesterna.
