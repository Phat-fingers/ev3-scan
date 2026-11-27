# EV3 Scan

Gratis Android-app som läser batteri- och laddningsdata direkt från en **Kia EV3** via en
Bluetooth OBD2-adapter. Ingen reklam, ingen inloggning, ingen data lämnar telefonen.

> 🇬🇧 *Free Android app that reads battery and charging data from a Kia EV3 through a
> Bluetooth LE (ELM327) OBD2 adapter. Swedish UI. See [English summary](#english-summary).*

<p>
  <img src="docs/laddning.jpg" width="240" alt="Laddning">
  <img src="docs/batteri.jpg" width="240" alt="Batteri">
  <img src="docs/status.jpg" width="240" alt="Status">
</p>

## Vad visas

**Batteri**
- SoC (både display-värdet och BMS-värdet) och SoH (batterihälsa)
- Kvarvarande energi enligt BMS (kWh)
- Effekt in/ut (kW), spänning och ström
- Batteritemperatur max/min, per modul och kylvätska
- Högsta/lägsta cell och skillnaden i mV, samt alla cellspänningar (fliken **Celler**)
- 12V-batteriets spänning, isolationsresistans
- Livstid: totalt laddad/förbrukad energi, drifttid

**Laddning**
- AC eller DC, om laddkabeln sitter i, laddsignal (CP)
- Laddmål från batteriet (begärd spänning/ström)
- Vid DC: laddstationens max och aktuella spänning/ström – så du ser om det är
  laddaren eller bilen som begränsar
- Tid sedan inkoppling, temperatur i laddintaget
- Toppeffekt under sessionen

**Logg**
- Rålogg och manuell kommandoinmatning
- **Dela rådata** – skickar alla råsvar som text (bra för felrapporter)

## Installera

1. Ladda ner senaste `EV3Scan-*.apk` under [**Releases**](../../releases/latest).
2. Öppna filen på telefonen och tillåt "installera okända appar" för webbläsaren/filhanteraren.
3. Sätt i adaptern i OBD-uttaget (under ratten, vänster sida).
4. Slå på bilen (READY) eller låt den ladda.
5. Öppna appen → **Sök adapter** → välj adaptern.
   *Parkoppla den inte i Androids Bluetooth-inställningar – appen ansluter själv.*

Kräver Android 8.0 eller senare.

## Adapter

Testad med **UniCarScan UCSI-2100**. Andra **Bluetooth LE**-adaptrar med ELM327-kommandon
(t.ex. Vgate iCar Pro BLE, OBDLink CX, Veepeak BLE) bör fungera men är inte testade.
Klassiska Bluetooth-adaptrar (SPP, ej "BLE"/"4.0") stöds inte.

## Status – vad är verifierat?

| Funktion | Status |
|---|---|
| Batteridata (SoC, SoH, temp, celler, ström/spänning) | ✅ Verifierat på riktig EV3 |
| DC-snabbladdning | ✅ Verifierat på riktig EV3 |
| Laddkabel ansluten / laddsignal | ✅ Verifierat (DC) |
| AC-laddning (hemma/laddbox) | ⚠️ Ej verifierat ännu |
| Andra modeller (EV6, Ioniq 5, EV9 m.fl.) | ❓ Kan delvis fungera, ej testat |

**Hjälp till:** Ser något fel ut? Gå till **Logg → Dela rådata** och skicka texten i en
[issue](../../issues) tillsammans med vad bilen gjorde (t.ex. "AC-laddar 11 kW hemma").

## Ansvarsfriskrivning

Inofficiell app, inte kopplad till eller godkänd av Kia. Appen **läser endast** data och
skickar inga kommandon som ändrar något i bilen. Använd på egen risk. Läs inte av appen
medan du kör.

## Hur det fungerar

Appen pratar med adaptern via BLE och skickar UDS-förfrågningar (tjänst `0x22`) till:

| Styrenhet | CAN-ID | DID |
|---|---|---|
| Batteri (BMS) | `7E4` → `7EC` | `0101` huvuddata, `0105` SoH/energi, `0102`–`010C` cellspänningar |
| Laddning (VCMS) | `744` → `74C` | `E001` laddläge, `E002` laddstation, `E003` CP/tider, `E004` kabel/relä |

Byte-positionerna bygger på [OBDb/Kia-EV3](https://github.com/OBDb/Kia-EV3)
(CC BY-SA 4.0), med rättelser från egna mätningar på en riktig EV3 (bl.a. bit för
laddkabel och tolkningen av laddmål). Riktiga svar från bilen ligger som enhetstester i
`app/src/test`.

## Bygga själv

GitHub Actions bygger, testar och publicerar en signerad APK vid varje push till `main`.
Lokalt: öppna projektet i Android Studio, eller kör `gradle assembleDebug`
(Gradle 8.11, JDK 17).

## Licens

Koden är licensierad under **GPL-3.0** – se [LICENSE](LICENSE). Du får använda, ändra och
dela den, men ändrade versioner som sprids måste också vara öppna under GPL-3.0.
Signaldefinitionerna från OBDb omfattas av CC BY-SA 4.0.

## English summary

EV3 Scan is a free, read-only Android app for the Kia EV3. With a Bluetooth LE ELM327 adapter
(tested: UniCarScan UCSI-2100) it shows SoC, SoH, battery/module temperatures, all cell voltages,
power, and — while DC fast charging — the BMS target current and the charger's limits, so you can
tell whether the car or the station is limiting. AC charging is not verified yet; raw-data dumps
(**Logg → Dela rådata**) in an issue are very welcome. Unofficial, not affiliated with Kia. Licensed under GPL-3.0.
