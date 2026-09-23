/**
 * Datetime-Splitter
 *
 * Warum: Das Backend speichert alle Zeitpunkte ausschliesslich in UTC als ISO-8601
 * (java.time.Instant -> "YYYY-MM-DDTHH:mm:ss(.SSS)Z"). Die Anzeige bzw. Eingabe
 * muss in der lokalen Zeitzone des Browsers erfolgen (DST-konform), damit der
 * Benutzer seine Lokalzeit sieht und eingibt. Dieses Skript ist die EINZIGE
 * Stelle, die in Formularen die Umrechnung UTC <-> Browser-Lokalzeit fuer
 * date-/time-Eingabefelder vornimmt.
 *
 * Hidden-Value-Vertrag:
 *   - Beim Laden akzeptiert: ISO-Instant mit 'Z' oder Offset, ISO-LocalDateTime
 *     (YYYY-MM-DDTHH:mm[:ss]) oder nur Datum (YYYY-MM-DD). Alles andere wird
 *     als Lokalzeit interpretiert (Fallback, rueckwaertskompatibel).
 *   - Beim Schreiben: immer UTC-ISO-Instant mit Sekunden und 'Z'
 *     (z.B. "2026-04-19T14:30:00Z").
 *
 * Oeffentliche API (window.krtSyncDatetimeSplitGroup):
 *   Ermoeglicht das erneute Synchronisieren der sichtbaren date/time-Parts aus
 *   dem hidden-Wert, nachdem dieser programmatisch gesetzt wurde (z.B. beim
 *   Oeffnen eines Bearbeiten-Modals). Ohne diesen Aufruf blieben die Parts
 *   leer, weil der Initialisierungs-Listener nur einmal beim DOMContentLoaded
 *   laeuft (Ursache fuer leere Check-in/Check-out-Felder im Teilnehmer-Edit).
 */
(function () {
    const pad = (n) => String(n).padStart(2, '0');
    // Erkennt, ob der Wert bereits Zonen-Information (Z oder +/-HH:MM) traegt.
    const hasZoneInfo = (val) => /Z$|[+-]\d{2}:?\d{2}$/.test(val);
    const isoDateRegex = /^\d{4}-\d{2}-\d{2}$/;
    const isoLocalRegex = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

    function applyHiddenToParts(hidden, datePart, timePart) {
        if (!hidden || !datePart || !timePart) return;
        if (!hidden.value) {
            datePart.value = '';
            timePart.value = '';
            return;
        }
        const v = hidden.value.trim();
        if (isoDateRegex.test(v)) {
            // Nur Datum, keine Uhrzeit
            datePart.value = v;
            timePart.value = '';
        } else if (hasZoneInfo(v)) {
            // UTC/Offset -> in Browser-Lokalzeit umrechnen
            const d = new Date(v);
            if (!isNaN(d)) {
                datePart.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
                timePart.value = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
            }
        } else if (isoLocalRegex.test(v)) {
            // Rueckwaertskompatibel: bereits Lokalzeit-String
            const parts = v.split('T');
            datePart.value = parts[0];
            timePart.value = parts[1].substring(0, 5);
        } else {
            // Letzter Fallback: versuchen als Date zu parsen und lokal darzustellen
            const d = new Date(v);
            if (!isNaN(d)) {
                datePart.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
                timePart.value = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
            }
        }
    }

    function syncGroup(group) {
        if (!group) return;
        const hidden = group.querySelector('input[type="hidden"]');
        const datePart = group.querySelector('.date-part');
        const timePart = group.querySelector('.time-part');
        applyHiddenToParts(hidden, datePart, timePart);
    }

    // Oeffentlich exponieren, damit Modal-Handler das erneute Synchronisieren
    // nach einem programmatischen Setzen des Hidden-Wertes ausloesen koennen.
    window.krtSyncDatetimeSplitGroup = syncGroup;

    /**
     * Initialisiert EINE .datetime-split-group (idempotent): haengt das Fehler-Div an,
     * befuellt die sichtbaren date/time-Felder aus dem Hidden-Wert und bindet die
     * change/input-Listener, die den Hidden-Wert (UTC-ISO) live aktualisieren. Ein bereits
     * initialisierter Block (Marker data-krt-dt-initialized) wird uebersprungen, damit ein
     * erneuter Aufruf nach einem Fragment-Swap weder ein zweites Fehler-Div anhaengt noch
     * die Listener doppelt bindet.
     */
    function initGroup(group) {
        const hidden = group.querySelector('input[type="hidden"]');
        const datePart = group.querySelector('.date-part');
        const timePart = group.querySelector('.time-part');

        if (!hidden || !datePart || !timePart) return;
        if (group.dataset.krtDtInitialized === '1') return;
        group.dataset.krtDtInitialized = '1';

        const errorDiv = document.createElement('div');
        errorDiv.style.color = 'var(--color-danger)';
        errorDiv.style.fontSize = '0.8rem';
        errorDiv.style.marginTop = '0.2rem';
        errorDiv.style.display = 'none';
        group.appendChild(errorDiv);

        // Lokale date-/time-Inputs aus dem Hidden-Wert initial befuellen.
        applyHiddenToParts(hidden, datePart, timePart);

        // Filter-Gruppen (z.B. Einsatzuebersicht) duerfen auch mit Teil-Eingaben
        // (nur Datum ohne Zeit, nur Zeit ohne Datum) einen gueltigen UTC-Instant
        // an das Backend senden. Die Rolle steuert sinnvolle Defaults:
        //   role="start" -> fehlende Zeit = 00:00 (Tagesanfang)
        //   role="end"   -> fehlende Zeit = 23:59 (Tagesende, inklusive)
        //   fehlendes Datum -> heute (Browser-Lokalzeit)
        const filterRole = group.getAttribute('data-datetime-filter-role');

        const updateHidden = () => {
            errorDiv.textContent = '';
            errorDiv.style.display = 'none';
            const dVal = datePart.value;
            const tVal = timePart.value;

            if (dVal && tVal) {
                // Lokale Eingabe -> Date-Objekt -> toISOString() liefert UTC mit 'Z'.
                const [y, m, d] = dVal.split('-').map(Number);
                const [hh, mm] = tVal.split(':').map(Number);
                const local = new Date(y, m - 1, d, hh, mm, 0, 0);
                hidden.value = isNaN(local) ? '' : local.toISOString();
            } else if (filterRole && (dVal || tVal)) {
                // Teil-Eingabe im Filter: fehlende Teile mit Defaults auffuellen.
                let y, m, d;
                if (dVal) {
                    [y, m, d] = dVal.split('-').map(Number);
                } else {
                    const now = new Date();
                    y = now.getFullYear();
                    m = now.getMonth() + 1;
                    d = now.getDate();
                }
                let hh, mm;
                if (tVal) {
                    [hh, mm] = tVal.split(':').map(Number);
                } else if (filterRole === 'end') {
                    hh = 23;
                    mm = 59;
                } else {
                    hh = 0;
                    mm = 0;
                }
                const local = new Date(y, m - 1, d, hh, mm, 0, 0);
                hidden.value = isNaN(local) ? '' : local.toISOString();
            } else if (dVal) {
                // Nur Datum (keine Filter-Rolle) -> als reines Datum (ohne Zone) uebermitteln
                hidden.value = dVal;
            } else {
                hidden.value = '';
            }

            if (hidden.value) {
                const currentDate = new Date();
                const selectedDate = new Date(hidden.value);

                if (group.getAttribute('data-validate-not-past') === 'true') {
                    if (selectedDate < currentDate) {
                        errorDiv.textContent = window.krtI18nText(
                            group.getAttribute('data-error-past'),
                            'data-error-past',
                        );
                        errorDiv.style.display = '';
                    }
                }

                if (group.hasAttribute('data-validate-after')) {
                    const targetId = group.getAttribute('data-validate-after');
                    const targetHidden = document.getElementById(targetId);
                    if (targetHidden && targetHidden.value) {
                        const targetDate = new Date(targetHidden.value);
                        if (selectedDate <= targetDate) {
                            errorDiv.textContent = window.krtI18nText(
                                group.getAttribute('data-error-after'),
                                'data-error-after',
                            );
                            errorDiv.style.display = '';
                        }
                    }
                }
            }

            hidden.dispatchEvent(new Event('change'));
        };

        datePart.addEventListener('change', updateHidden);
        timePart.addEventListener('change', updateHidden);
        datePart.addEventListener('input', updateHidden);
        timePart.addEventListener('input', updateHidden);
    }

    // Oeffentlich exponieren, damit nachgeladene Fragmente eine frische
    // .datetime-split-group selbst initialisieren koennen.
    window.krtInitDatetimeSplitGroup = initGroup;

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.datetime-split-group').forEach(initGroup);
    });

    // Nach einem In-Place-Fragment-Swap (krt-fetch.js dispatcht krt:swapped mit
    // detail.container) jede NEU eingefuegte .datetime-split-group initialisieren; der
    // Idempotenz-Marker laesst bereits initialisierte Bloecke unangetastet.
    document.addEventListener('krt:swapped', (e) => {
        const root = (e.detail && e.detail.container) || document;
        root.querySelectorAll('.datetime-split-group').forEach(initGroup);
    });
})();
