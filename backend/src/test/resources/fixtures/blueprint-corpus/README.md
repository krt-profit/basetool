# Anonymised game-log blueprint corpus

`game-log-corpus-v1.json` is the characterization corpus for blueprint name resolution
(krt-profit/basetool#2084, epic #2078). It holds the **31** blueprint unlocks found in the owner's
Star Citizen `logbackups` on 2026-09-26, in the SC Extractor's export shape (`schemaVersion` 1), so
`BlueprintExportParser` reads it unchanged.

What was kept, and what was replaced:

| Field | Source |
| --- | --- |
| `productName` | verbatim from the `Added notification "Received Blueprint: …"` line, including pack tags and a no-break space |
| `notificationId`, `queueSize` | verbatim from the same line |
| `gameBuild` | from the log file name |
| `player` | pseudonym `PLAYER_A`, assigned per log file from its login lines |
| `receivedAt` | synthetic — one hour apart from `2026-01-01T10:00:00Z`, original order kept |

Nothing else from the logs is in the file: no handle, account id, session id, path, file name or
real timestamp. `BlueprintCorpusFixtureTest` fails if one of those fields appears.

Do not replace names with look-alikes or normalise them — the corpus is only worth something because
the names are exactly what the game wrote.
