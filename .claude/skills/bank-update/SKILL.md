---
name: bank-update
description: Use this skill to write the monthly KRT-Bank report ("Bank Update") for the DAS KARTELL forum — the post that reports the Kartellbank's inflows and outflows for one calendar month, with the KRT account (the CARTEL account) broken out beside the bank total. It reads the figures from the production ledger read-only, reconciles them, and delivers a finished browser page whose "Formatiert kopieren" button puts forum-safe markup on the clipboard for the forum's CKEditor — never Markdown, never HTML source to hand-edit. Trigger it whenever the user wants the monthly bank report — e.g. "Bank Update für September", "Bankbericht für letzten Monat", "erstelle das Bankupdate", "Monatsbericht der KRT-Bank" — even when they don't name the month. Without a month it continues at the month after the last one reported, using a local progress marker (.bank-update-state.json) in the shared git directory.
user-invocable: true
---

# Bank Update für das KRT-Forum

Du erstellst den **monatlichen Bankbericht der KRT-Bank** für das Forum von
DAS KARTELL. Er berichtet die **Ein- und Ausgänge der gesamten Kartellbank für einen
Kalendermonat** und stellt das **KRT-Konto** gesondert daneben.

Das Ergebnis ist **keine Datei zum Nachbearbeiten**, sondern eine fertige Seite, aus
der der Nutzer per Knopfdruck in den CKEditor des Forums einfügt. Er bekommt nie
Markdown und nie HTML-Quelltext zu Gesicht — siehe Schritt 5.

> [!important] Vor der Arbeit: Knowledge Base lesen
> Die `CLAUDE.md` des Repos bindet dich darauf, vor jeder Aufgabe die **Basetool
> Knowledge Base** zu lesen. Für diesen Skill sind `20 Domains/Bank.md` (Hauptbuch,
> Kontoarten, Gebühren, Split-Einzahlung) und `60 Runbooks/Production Access.md`
> (Lesezugriff, psql-Rezept, **die Host-Adresse**) einschlägig. Findest du den Vault
> nicht, **frag** — rate keinen Pfad.

## Der Rhythmus: ein Kalendermonat, fällig am Monatsanfang

Ein Bericht deckt **immer einen vollen Kalendermonat** ab. Er wird **Anfang des
Folgemonats** geschrieben, und sein Stichtag („Stand") ist der **Erste des
Folgemonats**. Der Bericht für den September ist also Anfang Oktober fällig und trägt
den Stand `01.10.`.

- **Ohne Monatsangabe** nimmst du den Monat nach dem zuletzt berichteten (Marker,
  siehe unten); gibt es noch keinen Marker, den **letzten vollen** Kalendermonat.
- **Ein laufender Monat wird nicht berichtet.** Das Hilfsskript verweigert ihn von
  selbst. Fragt der Nutzer ausdrücklich nach einem Zwischenstand, geht das mit
  `--allow-partial` — dann aber im Text ausdrücklich als Zwischenstand kennzeichnen
  und den Marker **nicht** setzen.

## Fortschrittsmarker (lokal, nie committet)

Damit niemand sich merken muss, welcher Monat zuletzt dran war:

```bash
python .claude/skills/bank-update/scripts/bank_update_state.py --show
```

Die Datei `.bank-update-state.json` liegt im **gemeinsamen Git-Verzeichnis**
(`git rev-parse --git-common-dir`), also außerhalb jedes Arbeitsbaums. Das ist der
entscheidende Punkt: Jede Sitzung läuft in einem frischen Wegwerf-Worktree, und
git-ignorierte Dateien wandern *nicht* zwischen Worktrees — ein Marker im
Arbeitsbaum wäre beim nächsten Lauf unsichtbar. Der Marker geht nur vorwärts und
wird **ganz am Ende** gesetzt (Schritt 6), nie vorher.

## Schritt 1 — Zahlen holen (Produktion, nur lesend)

```bash
python .claude/skills/bank-update/scripts/fetch_bank_figures.py --month 2026-09
# ohne --month: der letzte volle Kalendermonat
```

Das Skript öffnet **eine** psql-Sitzung auf dem Produktionshost, die mit
`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` beginnt, und gibt alle Zahlen
fertig gerechnet aus. Lesen ist ohne Freigabe erlaubt; **schreiben ist es nie** —
dieser Skill schreibt auf dem Host nichts und darf es auch nicht.

> [!warning] Die Host-Adresse steht **nicht** in diesem Repository
> `krt-profit/basetool` ist öffentlich. Setz die Adresse einmalig als
> Umgebungsvariable, oder gib sie mit `--host` mit:
>
> ```bash
> $env:BASETOOL_PROD_HOST = "<benutzer>@<adresse>"   # PowerShell
> ```
>
> Wo die Adresse steht: Knowledge Base, `60 Runbooks/Production Access.md`. Ist die
> Variable nicht gesetzt, sagt das Skript das und bricht ab — **frag dann den
> Nutzer**, schreib die Adresse nirgends ins Repo.

> [!note] Seit 2026-09-22 über Podman
> Die Produktion läuft als **rootless Podman** unter dem Dienstkonto `iri` (arc42 §7). Das
> Skript setzt deshalb `cd / && sudo -n -u iri podman exec -i db-backend …` ab — dieselbe Form
> wie `rt_exec` in `scripts/lib/container-runtime.sh`. Das `cd /` ist nötig, weil die
> SSH-Sitzung in `/root` beginnt, das `iri` nicht betreten darf. Am 2026-09-22 mit einer
> Nur-Lese-Abfrage auf dem Host geprüft.

> [!note] SSH geht auf diesem Rechner nur über den Windows-Client
> Das Skript wählt `C:\Windows\System32\OpenSSH\ssh.exe` selbst, wenn es existiert.
> Der MSYS-/Git-Bash-`ssh` erreicht den Windows-ssh-agent nicht und scheitert mit
> `Permission denied (publickey)`. Führ das Skript deshalb über **PowerShell** aus.

### Die Gegenprobe ist bindend

Ganz unten steht für Bank und KRT-Konto jeweils „Anfang + Bewegungen = Ende".
**Steht dort `FEHLER`, wird nichts veröffentlicht** — dann stimmt eine Annahme nicht,
und das ist zu klären, nicht zu glätten. Das Skript beendet sich in dem Fall selbst
mit Fehler.

### Was die Zahlen bedeuten

- **Einzahlungen / Auszahlungen** sind die echten Zu- und Abflüsse (`DEPOSIT` /
  `WITHDRAWAL`). Sie sind die **Hauptzahlen** des Berichts.
- **Umbuchungen** (`TRANSFER`, `HOLDER_TRANSFER`) verschieben nur zwischen Konten und
  sind bankweit netto null. **Stornierungen** (`REVERSAL`) sind Korrekturen.
  Beide erscheinen im Bericht **nur, wenn sie ungleich null sind** — sonst stattdessen
  der Satz, dass es keine gab (siehe Textbaustein unten).
- **Eine Einzahlung kann auf viele Konten gebucht sein.** Eine Split-Einzahlung
  verteilt einen Anteil über alle aktiven Staffelkonten; die Buchungsbeine summieren
  sich aber **exakt** auf den Bruttobetrag, weil der Anteil des benannten Kontos per
  Subtraktion entsteht. 13 Einzahlungen mit 41 Buchungsbeinen sind deshalb **keine**
  Doppelzählung. Näheres in `20 Domains/Bank.md`.
- **Die Transfergebühr steckt in der Auszahlung drin**, sie kommt nicht obendrauf. Im
  Text also „darin X aUEC Transfergebühren", nie „zuzüglich".
- Das **KRT-Konto** ist das Konto vom Typ `CARTEL`. Nie über die Kontonummer
  ansprechen — das Skript löst es über den Typ auf und bricht ab, wenn es nicht
  genau eines gibt.

## Schritt 2 — Die drei stehenden Festlegungen

Diese sind entschieden und werden **nicht jeden Monat neu gefragt**:

1. **Keine Namen.** Der Bericht nennt **keine** Spender, Halter oder Antragsteller.
   Das ist keine Stilfrage: die `CLAUDE.md` verbietet, Personennamen oder Handles vom
   Produktionshost in ein Artefakt zu schreiben. Stattdessen Anzahlen („13
   Einzahlungen"). **Will der Nutzer Namen, muss er die Regel ausdrücklich für diesen
   Lauf aufheben** — dann erst liest du `counterparty_handle` aus.
2. **Keine Star-Citizen-Version.** Ältere Berichte schrieben „in der Alpha 4.1". Die
   Version steht in keiner Datenquelle; sie wird weggelassen, nicht geraten.
3. **Summen = externe Zu-/Abflüsse.** Umbuchungen zwischen Konten blähen beide Summen
   auf und werden deshalb nicht hineingerechnet, sondern separat genannt.

Nachfragen musst du nur, wenn es **besondere Vorkommnisse** gab, die die Zahlen nicht
erklären — eine große Aktion hinter einer Einzahlung, ein geplantes Vorhaben für den
Ausblick. Gab es nichts, bleibt der Ausblick beim stehenden Satz.

## Schritt 3 — Ton

Der Ton ist **der des Nutzers, nicht der der alten Vorlagen**. Die alten Berichte
waren werblich („wäre es lobenswert", „Jeder aUEC zählt hier", Smiley). So nicht.

- **Knapp und direkt.** Sag die Sache, dann hör auf. Imperativ statt Appell.
- **Keine Appell-Sprache, keine Smileys, keine Emojis.**
- **Zahlen sprechen für sich.** Ein Superlativ nur, wenn die Zahl ihn trägt.
- **Echte Umlaute** (ä/ö/ü/ß), deutsche Tausenderpunkte, Einheit immer `aUEC`.
- Für Beträge im Fließtext das Minuszeichen „−" (U+2212), nicht den Bindestrich.

## Schritt 4 — Das Fragment schreiben

Schreib den Beitrag als **HTML-Fragment** in eine Datei im Scratchpad (z. B.
`post.html`). Das Ziel ist derselbe CKEditor 5 im WoltLab-Forum wie beim
[`release-notes`](../release-notes/SKILL.md)-Skill, und **die Formatregeln sind
dieselben** — bleib mit ihm in Gleichschritt, wenn sich dort etwas ändert.

- **Erlaubte Tags:** `<h2>`, `<h3>`, `<p>`, `<ul>`, `<li>`, `<strong>`, `<em>`, `<a>`
  (nur `href`) — plus `<table>` mit `<thead>`, `<tbody>`, `<tr>`, `<th>`, `<td>`.
  Sonst nichts: **kein `<div>`, `<span>`, `<style>`, `class`, `id` oder Inline-Style**,
  und kein umschließendes Wurzelelement. CKEditor filtert beim Einfügen alles heraus,
  was seine Konfiguration nicht kennt.
- **Überschriften beginnen bei `<h2>`.** Genau **ein** `<h2>` — der Titel, immer der
  erste Block. Jeder Abschnitt ist ein `<h3>`. **Kein `<h1>`** (das gehört dem
  Thread-Titel) und **kein `<h4>` oder tiefer**.
- **Echte UTF-8-Umlaute** — `ä ö ü ß` als Zeichen, **niemals** als HTML-Entity
  (`&uuml;`) und niemals als `\uXXXX`. Die Entity-Schreibweise rendert zwar korrekt,
  macht aber die Quelltext-Ansicht unlesbar. (Der August-Bericht 2026 verwendete noch
  Entities — harmlos, aber nicht das Vorbild.)
- **Keine Emojis**, nirgends.

> [!note] Die Tabelle ist die eine bewusste Erweiterung — und sie ist verifiziert
> Die Acht-Tag-Liste des `release-notes`-Skills kennt keine Tabelle, weil sie dort
> nicht gebraucht wird. **Am 2026-09-16 am Live-Editor geprüft:** `<table>` kommt
> unverändert durch, und das Forum gestaltet sie mit seinem eigenen Theme — erste
> Spalte in Hausorange, Schlusszeile fett. `<h2>`, `<h3>`, `<strong>`, das Minuszeichen
> U+2212 und der Geviertstrich ebenso. Genau **deshalb** trägt das Fragment keine
> eigenen Farben: ein gestyltes Fragment würde gegen das Theme arbeiten.

Gerüst (die **fett** markierten Blöcke sind feststehender Text und werden wörtlich
übernommen):

```html
<h2>Bank Update {MONAT} {JAHR}</h2>
<p>{Einleitung: ein bis zwei Sätze, die den Monat einordnen.}</p>

<h3>1. Ein- und Ausgänge im Überblick</h3>
<table>
<thead>
<tr><th></th><th>Gesamte Kartellbank</th><th>davon KRT-Konto</th></tr>
</thead>
<tbody>
<tr><th>Kontostand {01.MM.JJJJ}</th><td>…</td><td>…</td></tr>
<tr><th>Einzahlungen</th><td>+…</td><td>+…</td></tr>
<tr><th>Auszahlungen</th><td>−…</td><td>−…</td></tr>
<tr><th>Saldo {Monat}</th><td>+…</td><td>+…</td></tr>
<tr><th>Kontostand {01.MM.JJJJ}</th><td>…</td><td>…</td></tr>
</tbody>
</table>
<p>Alle Beträge in aUEC. {Anzahl Ein-/Auszahlungen, Gebühren, Konten mit Bewegung,
Satz zu Umbuchungen/Stornierungen.}</p>

<h3>2. Update Kontostand der KRT-Bank</h3>
<p>{Stand, Zuwachs/Rückgang, wodurch. Dann:}</p>
<p>Damit liegen rund {X} Prozent des gesamten Bankvermögens auf dem KRT-Konto.</p>
<p>{Abrufe-Absatz.}</p>
<p>Die aktuellen Daten des KRT-Kontos könnt ihr im Profit Basetool einsehen.</p>

<h3>3. Hinweis</h3>
<p>Wenn ihr aUEC übrig habt, zahlt sie gerne in die Bank ein. Ob großer Trade,
verkaufter Loot oder eine profitable Mining-Session — es spielt keine Rolle,
es zählt alles. Freiwillig bleibt es trotzdem.</p>
<p>Einzahlungen, Auszahlungen und Anträge laufen per Profit Basetool oder direkt
über die Bankmitarbeiter.</p>

<h3>4. Aussicht</h3>
<p>Kurzfristig sind keine großen Neuerung geplant.</p>
<p>Weitere Informationen zur KRT-Bank findet ihr <a
href="https://das-kartell.org/forum/thread/7898-krt-bank-v2-0/?postID=292205#post292205">in
diesem Beitrag</a>.</p>

<p>Grüße vom Team eurer KRT-Bank und bis zum nächsten Update.</p>
```

### Die feststehenden Bausteine

**Abschnitt 3, Abschnitt 4 und die Grußzeile sind Fließtext-Bausteine und werden
wörtlich übernommen.** Sie sind vom Nutzer selbst formuliert; formuliere sie nicht um
und „verbessere" sie nicht.

> [!note] „keine großen Neuerung" bleibt so
> Grammatisch müsste es „Neuerungen" heißen. Der Nutzer wurde darauf hingewiesen und
> hat den Wortlaut so behalten. **Nicht erneut anmerken und nicht stillschweigend
> korrigieren.**

Der **Ausblick** ist der einzige Baustein, der sich ändert: Nennt der Nutzer ein
Vorhaben, ersetzt es den Satz; sonst bleibt er stehen.

### Sätze, die von den Zahlen abhängen

- **Gab es keine Umbuchungen und keine Stornierungen** (Normalfall): „Umbuchungen
  zwischen Konten und Stornierungen gab es im {Monat} keine, die Zahlen oben sind also
  durchweg echte Zu- und Abflüsse."
- **Gab es welche:** nenn sie in einem eigenen Satz mit Betrag und sag, dass sie in
  den Summen oben **nicht** enthalten sind, im Kontostand aber sehr wohl.
- **Abrufe:** Anzahl bestätigter Auszahlungsanträge, davon die auf dem KRT-Konto mit
  Betrag; abgelehnte gesondert. Gab es keine: „Anfragen bezüglich Abrufe gab es keine."

## Schritt 5 — Seite bauen und ausliefern

```bash
python .claude/skills/bank-update/scripts/build_page.py \
  --fragment post.html \
  --title "Bank Update September 2026" \
  --subline "Stand 01.10.2026 · Buchungen vom 01.09. bis 30.09.2026" \
  --out bank-update-2026-09.html
```

Dann die Datei **mit dem Artifact-Tool veröffentlichen** und dem Nutzer den Link
geben. Das ist die Auslieferung — kein Markdown im Chat, kein HTML-Quelltext zum
Selberbasteln.

Die Seite trägt zwei Knöpfe: **„Formatiert kopieren"** legt den Beitrag als Rich-Text
in die Zwischenablage (im CKEditor `Strg+V`, das Markup ist bewusst stillos, damit
keine fremden Inline-Styles ins Forum wandern), **„HTML-Quelltext kopieren"** ist der
Rückfallweg für die Quelltext-Ansicht.

- **Seitengerüst nie im Fragment nachbauen.** Es kommt aus
  `assets/report-page.html`, damit jeder Monat gleich aussieht. Ändert sich das
  Aussehen, ändert sich die Vorlage — nicht der Monatstext.
- `build_page.py` bricht ab, wenn im Fragment noch ein Platzhalter steht. Das ist
  Absicht: ein sichtbarer Platzhalter im Forum wäre peinlich.
- Beim **Aktualisieren** desselben Berichts denselben Dateipfad erneut
  veröffentlichen, dann bleibt die URL gleich.

## Schritt 6 — Marker setzen

Erst **wenn der Bericht fertig und veröffentlicht ist**:

```bash
python .claude/skills/bank-update/scripts/bank_update_state.py --set 2026-09
```

Bei einem Zwischenstand (`--allow-partial`) **nicht** setzen — der Monat ist noch
nicht abgeschlossen und muss regulär noch einmal drankommen.

## Schnell-Checkliste vor dem Abschluss

- [ ] Knowledge Base gelesen (`20 Domains/Bank.md`), bei Abweichung Code vor Notiz.
- [ ] Voller Kalendermonat, Stichtag = Erster des Folgemonats.
- [ ] Gegenprobe des Hilfsskripts steht auf `OK` — bei `FEHLER` nichts veröffentlicht.
- [ ] Keine Namen, keine Handles, keine Alpha-Version im Text.
- [ ] Tabelle mit beiden Spalten: Gesamte Kartellbank **und** davon KRT-Konto.
- [ ] Gebühren als „darin enthalten" formuliert, nicht als Aufschlag.
- [ ] Satz zu Umbuchungen/Stornierungen passt zu den tatsächlichen Zahlen.
- [ ] Abschnitte 3, 4 und Grußzeile wörtlich übernommen, nichts umformuliert.
- [ ] Genau ein `<h2>` (der Titel), Abschnitte als `<h3>`, **kein `<h1>`**, kein `<h4>`+.
- [ ] Nur erlaubte Tags; keine Styles, Klassen, `<div>`; echte UTF-8-Umlaute statt Entities.
- [ ] „−“ (U+2212) für negative Beträge, keine Emojis.
- [ ] Seite mit `build_page.py` gebaut und als Artifact veröffentlicht, Link genannt.
- [ ] Marker gesetzt (außer bei Zwischenstand).
- [ ] Auf dem Produktionshost wurde **nur gelesen**.
