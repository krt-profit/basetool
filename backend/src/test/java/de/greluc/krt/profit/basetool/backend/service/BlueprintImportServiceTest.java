/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportStatus;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link BlueprintImportService}. */
@ExtendWith(MockitoExtension.class)
class BlueprintImportServiceTest {

  private static final UUID SUB = UUID.fromString("0e000001-0000-4000-8000-000000000001");
  private static final BlueprintExternalAliasSource SCMDB = BlueprintExternalAliasSource.SCMDB;

  @Mock private BlueprintProductService blueprintProductService;
  @Mock private BlueprintExternalAliasRepository aliasRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private GameItemRepository gameItemRepository;

  private BlueprintImportService service;

  @BeforeEach
  void setUp() {
    service =
        new BlueprintImportService(
            JsonMapper.builder().build(),
            blueprintProductService,
            new BlueprintNameNormalizer(),
            new BlueprintFuzzyMatcher(),
            aliasRepository,
            personalBlueprintRepository,
            gameItemRepository);
  }

  private static ResolvedProduct product(String key, String name) {
    return new ResolvedProduct(key, name, null);
  }

  private static MultipartFile upload(String json) {
    return new MockMultipartFile("file", "scmdb.json", "application/json", json.getBytes());
  }

  @Test
  void previewImport_fileExceedingSizeCap_rejectedBeforeParsing() {
    // Security audit gap-fill: an oversized upload is rejected by file.getSize() BEFORE readTree
    // materialises the JSON into an in-memory tree.
    MultipartFile file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(9L * 1024 * 1024); // > 8 MB cap

    assertThrows(BadRequestException.class, () -> service.previewImport(SUB, file));
  }

  // ---------------------------------------------------------------- preview --

  @Test
  void preview_exactNameMatches() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB, upload("{\"blueprints\":[{\"productName\":\"Arclight Pistol\"}]}"));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED, entry.status());
    assertEquals("arclight pistol", entry.productKey());
  }

  @Test
  void preview_aliasMatches_whenNoExactButAliasExists() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    BlueprintExternalAlias alias = new BlueprintExternalAlias();
    alias.setSourceSystem(SCMDB);
    alias.setExternalName("Arc-Light Pistol");
    alias.setProductKey("arclight pistol");
    alias.setProductName("Arclight Pistol");
    when(aliasRepository.findBySourceSystemAndExternalNameIgnoreCase(SCMDB, "Arc-Light Pistol"))
        .thenReturn(Optional.of(alias));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB, upload("{\"blueprints\":[{\"productName\":\"Arc-Light Pistol\"}]}"));

    assertEquals(1, preview.matchedByAlias());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED_BY_ALIAS, entry.status());
    assertEquals("arclight pistol", entry.productKey());
  }

  @Test
  void preview_aliasFallsBackToSnapshotWhenProductGoneFromMaster() {
    // A learned alias must never silently regress to UNMATCHED when the master product it points at
    // is later renamed / removed (its productKey is gone from allProducts()). resolveViaAlias then
    // dereferences the alias's own productKey / productName / outputItem snapshot instead of the
    // (now-missing) master row, so the entry stays MATCHED_BY_ALIAS and the user's
    // previously-learned
    // resolution keeps working.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    BlueprintExternalAlias alias = new BlueprintExternalAlias();
    alias.setSourceSystem(SCMDB);
    alias.setExternalName("Gone Product");
    alias.setProductKey("gone-key");
    alias.setProductName("Gone Product");
    // Master no longer carries 'gone-key' — only the unrelated 'arclight pistol' is present.
    when(aliasRepository.findBySourceSystemAndExternalNameIgnoreCase(SCMDB, "Gone Product"))
        .thenReturn(Optional.of(alias));

    BlueprintImportPreviewDto preview =
        service.previewImport(SUB, upload("{\"blueprints\":[{\"productName\":\"Gone Product\"}]}"));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matchedByAlias());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED_BY_ALIAS, entry.status());
    assertEquals("gone-key", entry.productKey());
    assertEquals("Gone Product", entry.productName());
    assertNull(entry.outputItemId());
  }

  @Test
  void preview_fuzzySuggestsForCloseTypo() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB, upload("{\"blueprints\":[{\"productName\":\"Calico Legs Tacticl\"}]}"));

    assertEquals(1, preview.suggested());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.SUGGESTED, entry.status());
    assertNull(entry.productKey());
    assertEquals("calico legs tactical", entry.suggestions().get(0).productKey());
  }

  @Test
  void preview_unmatchedWhenNothingClose() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB, upload("{\"blueprints\":[{\"productName\":\"Quantum Drive XL\"}]}"));

    assertEquals(1, preview.unmatched());
    assertEquals(BlueprintImportStatus.UNMATCHED, preview.entries().get(0).status());
  }

  @Test
  void preview_alreadyOwnedOverridesMatched() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    PersonalBlueprint owned =
        PersonalBlueprint.builder().ownerUserId(SUB).productKey("arclight pistol").build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB, upload("{\"blueprints\":[{\"productName\":\"Arclight Pistol\"}]}"));

    assertEquals(0, preview.matched());
    assertEquals(1, preview.alreadyOwned());
    assertEquals(BlueprintImportStatus.ALREADY_OWNED, preview.entries().get(0).status());
  }

  @Test
  void preview_dedupesByNameAndKeepsEarliestTimestamp() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // Two records of the same product; the later ts appears first in the file.
    String json =
        "{\"blueprints\":["
            + "{\"productName\":\"Arclight Pistol\",\"ts\":1774534484.0},"
            + "{\"productName\":\"Arclight Pistol\",\"ts\":1700000000.0}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(
        Instant.ofEpochMilli(1700000000000L), preview.entries().get(0).suggestedAcquiredAt());
  }

  @Test
  void preview_acceptsBareArrayForm() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(SUB, upload("[{\"productName\":\"Arclight Pistol\"}]"));

    assertEquals(1, preview.matched());
  }

  @Test
  void preview_acceptsFullScmdbWatcherDocumentShape() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));

    // Exact shape the original SCMDB log-watcher import mode (run_import) writes: a metadata
    // envelope plus a blueprints[] of entries carrying productName + ts (fractional epoch SECONDS,
    // value taken from a real Game.log line) and mission-correlation fields the import ignores.
    String json =
        "{\"exportSchemaVersion\":1,\"watcherVersion\":\"0.1.7\",\"channel\":\"LIVE\","
            + "\"exportedAt\":\"2026-03-26T17:00:00+00:00\",\"sourceLogs\":[\"Game Build(1).log\"],"
            + "\"missions\":[],"
            + "\"blueprints\":[{\"productName\":\"Calico Legs Tactical\",\"ts\":1774534484.296,"
            + "\"missionGuid\":\"g\",\"missionDebugName\":\"d\","
            + "\"missionContractDefinitionId\":\"c\",\"missionTrigger\":\"complete\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals("calico legs tactical", entry.productKey());
    // ts is epoch SECONDS; 1774534484.296 -> 2026-03-26T14:14:44.296Z (verified against the
    // watcher).
    assertEquals(Instant.parse("2026-03-26T14:14:44.296Z"), entry.suggestedAcquiredAt());
  }

  @Test
  void preview_acceptsBpExtractorReceivedAtFormat() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // Basetool Blueprint Extractor shape: full document with receivedAt (ISO-8601) instead of ts.
    // Deliberately has no additionalSourceFolders key — pins that exports from extractor versions
    // predating that additive v1 field keep importing unchanged.
    String json =
        "{\"schemaVersion\":1,\"tool\":\"Basetool Blueprint Extractor\","
            + "\"players\":[{\"handle\":\"greluc\",\"blueprintCount\":1}],"
            + "\"blueprints\":[{\"productName\":\"Arclight Pistol\",\"category\":\"Weapon\","
            + "\"receivedAt\":\"2026-03-26T16:49:31.050Z\",\"player\":\"greluc\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED, entry.status());
    assertEquals("arclight pistol", entry.productKey());
    assertEquals(Instant.parse("2026-03-26T16:49:31.050Z"), entry.suggestedAcquiredAt());
  }

  @Test
  void preview_acceptsBpExtractorWithAdditionalSourceFolders() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // Blueprint Extractor envelope with the additive v1 field additionalSourceFolders populated
    // (the extractor also scanned the HOTFIX sibling channel beside sourceFolder). The envelope is
    // provenance only — the import must resolve the entries exactly as without it.
    String json =
        "{\"schemaVersion\":1,\"tool\":\"Basetool Blueprint Extractor\","
            + "\"sourceFolder\":\"C:\\\\Games\\\\StarCitizen\\\\LIVE\","
            + "\"additionalSourceFolders\":[\"C:\\\\Games\\\\StarCitizen\\\\HOTFIX\"],"
            + "\"blueprints\":[{\"productName\":\"Arclight Pistol\","
            + "\"receivedAt\":\"2026-03-26T16:49:31.050Z\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED, entry.status());
    assertEquals("arclight pistol", entry.productKey());
    assertEquals(Instant.parse("2026-03-26T16:49:31.050Z"), entry.suggestedAcquiredAt());
  }

  @Test
  void preview_acceptsBpExtractorWithNullAdditionalSourceFolders() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // The extractor encodes defaults, so a single-folder scan writes the key with a JSON null
    // instead of omitting it — that explicit null must parse like an absent key.
    String json =
        "{\"schemaVersion\":1,\"tool\":\"Basetool Blueprint Extractor\","
            + "\"sourceFolder\":\"C:\\\\Games\\\\StarCitizen\\\\LIVE\","
            + "\"additionalSourceFolders\":null,"
            + "\"blueprints\":[{\"productName\":\"Arclight Pistol\","
            + "\"receivedAt\":\"2026-03-26T16:49:31.050Z\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    assertEquals("arclight pistol", preview.entries().get(0).productKey());
  }

  // ------------------------------------------------------- scmdb.net export --

  @Test
  void preview_acceptsScmdbNetNameAlias() {
    // covers REQ-INV-014 — scmdb.net entries name the product under `name`, not `productName`.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(SUB, upload("{\"blueprints\":[{\"name\":\"Arclight Pistol\"}]}"));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED, entry.status());
    assertEquals("arclight pistol", entry.productKey());
    // scmdb.net carries no acquisition timestamp.
    assertNull(entry.suggestedAcquiredAt());
  }

  @Test
  void preview_acceptsFullScmdbNetProfileExportIgnoringMissions() {
    // covers REQ-INV-014 — the scmdb.net profile export wraps blueprints[] alongside a profile and
    // a
    // missions[] tracker; only blueprints[] is consumed, every other envelope field is ignored.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("p8-ar rifle", "P8-AR Rifle")));

    String json =
        "{\"version\":3,\"exportedAt\":\"2026-06-21T17:47:57.354Z\","
            + "\"profile\":{\"displayName\":\"greluc\",\"rsi\":{\"handle\":\"greluc\"}},"
            + "\"missions\":[{\"hash\":\"fvv4mu\",\"name\":\"A Well Deserved Break\","
            + "\"completed\":true,\"favorite\":false}],"
            + "\"blueprints\":[{\"tag\":\"BP_CRAFT_behr_rifle_ballistic_02_civilian\","
            + "\"name\":\"P8-AR Rifle\",\"url\":\"https://scmdb.net/?page=fab&fab=BP_X\","
            + "\"completed\":true,\"favorite\":false}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    assertEquals("p8-ar rifle", preview.entries().get(0).productKey());
    // The missions[] tracker must never leak into the import — only the one blueprint is present.
    assertEquals(
        List.of("P8-AR Rifle"),
        preview.entries().stream().map(BlueprintImportEntryDto::externalName).toList());
  }

  @Test
  void preview_skipsNotCompletedScmdbNetEntries() {
    // covers REQ-INV-014 — an scmdb.net entry the user has not unlocked (completed == false) is a
    // checklist placeholder, not an owned blueprint, and must be dropped before resolution.
    when(blueprintProductService.allProducts())
        .thenReturn(
            List.of(
                product("arclight pistol", "Arclight Pistol"),
                product("p8-ar rifle", "P8-AR Rifle")));

    String json =
        "{\"blueprints\":["
            + "{\"name\":\"Arclight Pistol\",\"completed\":true},"
            + "{\"name\":\"P8-AR Rifle\",\"completed\":false}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(1, preview.matched());
    assertEquals("arclight pistol", preview.entries().get(0).productKey());
  }

  @Test
  void preview_matchesByTagWhenNameWouldNotMatch() {
    // covers REQ-INV-019 — the scmdb.net structural tag resolves straight to the product, bypassing
    // the name chain. The name here is deliberately unmatchable (mirrors a CIG-mislabeled output
    // name, REQ-INV-007) yet the entry still resolves via its DataForge tag.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("antium arms maroon", "Antium Arms Maroon")));
    when(blueprintProductService.scwikiKeyToProductKeyIndex())
        .thenReturn(Map.of("bp_craft_qrt_specialist_heavy_arms_01_01_13", "antium arms maroon"));

    String json =
        "{\"blueprints\":[{\"name\":\"Totally Different Name\","
            + "\"tag\":\"BP_CRAFT_qrt_specialist_heavy_arms_01_01_13\",\"completed\":true}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.matched());
    BlueprintImportEntryDto entry = preview.entries().get(0);
    assertEquals(BlueprintImportStatus.MATCHED, entry.status());
    assertEquals("antium arms maroon", entry.productKey());
    assertEquals("Antium Arms Maroon", entry.productName());
    assertEquals("Totally Different Name", entry.externalName());
  }

  @Test
  void preview_tagMatchIsCaseInsensitive() {
    // covers REQ-INV-019 — the Wiki keeps CamelCase scwiki_keys, scmdb.net lower-cases them; the
    // tag
    // index is lower-cased so the two spellings still meet.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    when(blueprintProductService.scwikiKeyToProductKeyIndex())
        .thenReturn(Map.of("bp_craft_amrs_lasercannon_s1", "arclight pistol"));

    String json =
        "{\"blueprints\":[{\"name\":\"Mislabelled\","
            + "\"tag\":\"BP_CRAFT_AMRS_LaserCannon_S1\",\"completed\":true}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.matched());
    assertEquals("arclight pistol", preview.entries().get(0).productKey());
  }

  @Test
  void preview_tagMissFallsBackToNameChain() {
    // covers REQ-INV-019 — a tag absent from the index (unsynced or ambiguous blueprint) is not an
    // error: resolution falls through to the normal name match.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    when(blueprintProductService.scwikiKeyToProductKeyIndex()).thenReturn(Map.of());

    String json =
        "{\"blueprints\":[{\"name\":\"Arclight Pistol\","
            + "\"tag\":\"BP_CRAFT_unknown_key\",\"completed\":true}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.matched());
    assertEquals("arclight pistol", preview.entries().get(0).productKey());
  }

  @Test
  void preview_acceptsBareArrayOfScmdbNetEntries() {
    // covers REQ-INV-014 — the bare-array upload form also accepts scmdb.net-shaped records.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB,
            upload(
                "[{\"tag\":\"BP_X\",\"name\":\"Arclight Pistol\",\"completed\":true,"
                    + "\"favorite\":false}]"));

    assertEquals(1, preview.matched());
    assertEquals("arclight pistol", preview.entries().get(0).productKey());
  }

  @Test
  void preview_keepsDistinctTagsUnderSameName() {
    // covers REQ-INV-019 — two scmdb.net blueprints sharing a display name but carrying different
    // DataForge tags (a genuine piece and a CIG-mislabeled one both shown as "Antium Core Jet",
    // REQ-INV-007) must NOT collapse by name: each resolves via its own tag to its own product.
    when(blueprintProductService.allProducts())
        .thenReturn(
            List.of(
                product("antium core jet", "Antium Core Jet"),
                product("antium helmet jet", "Antium Helmet Jet")));
    when(blueprintProductService.scwikiKeyToProductKeyIndex())
        .thenReturn(
            Map.of(
                "bp_craft_qrt_specialist_heavy_core_01_01_12", "antium core jet",
                "bp_craft_qrt_specialist_heavy_helmet_01_01_12", "antium helmet jet"));

    String json =
        "{\"blueprints\":["
            + "{\"name\":\"Antium Core Jet\","
            + "\"tag\":\"BP_CRAFT_qrt_specialist_heavy_core_01_01_12\",\"completed\":true},"
            + "{\"name\":\"Antium Core Jet\","
            + "\"tag\":\"BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12\",\"completed\":true}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(2, preview.total());
    assertEquals(2, preview.matched());
    List<String> keys =
        preview.entries().stream().map(BlueprintImportEntryDto::productKey).sorted().toList();
    assertEquals(List.of("antium core jet", "antium helmet jet"), keys);
  }

  @Test
  void preview_prefersTsOverReceivedAtWhenBothPresent() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // ts (epoch seconds) wins over receivedAt when an export carries both.
    String json =
        "{\"blueprints\":[{\"productName\":\"Arclight Pistol\",\"ts\":1700000000.0,"
            + "\"receivedAt\":\"2026-03-26T16:49:31.050Z\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(
        Instant.ofEpochMilli(1700000000000L), preview.entries().get(0).suggestedAcquiredAt());
  }

  @Test
  void preview_dedupesBpExtractorEntriesKeepingEarliestReceivedAt() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    // Two records of the same product; the later receivedAt appears first in the file.
    String json =
        "{\"blueprints\":["
            + "{\"productName\":\"Arclight Pistol\",\"receivedAt\":\"2026-03-26T16:49:31.050Z\"},"
            + "{\"productName\":\"Arclight Pistol\",\"receivedAt\":\"2025-01-01T00:00:00.000Z\"}]}";
    BlueprintImportPreviewDto preview = service.previewImport(SUB, upload(json));

    assertEquals(1, preview.total());
    assertEquals(
        Instant.parse("2025-01-01T00:00:00.000Z"), preview.entries().get(0).suggestedAcquiredAt());
  }

  @Test
  void preview_ignoresUnparseableReceivedAt() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportPreviewDto preview =
        service.previewImport(
            SUB,
            upload(
                "{\"blueprints\":[{\"productName\":\"Arclight"
                    + " Pistol\",\"receivedAt\":\"nope\"}]}"));

    assertEquals(1, preview.matched());
    assertNull(preview.entries().get(0).suggestedAcquiredAt());
  }

  @Test
  void preview_emptyFileThrowsBadRequest() {
    MultipartFile empty =
        new MockMultipartFile("file", "scmdb.json", "application/json", new byte[0]);
    assertThrows(BadRequestException.class, () -> service.previewImport(SUB, empty));
  }

  @Test
  void preview_malformedJsonThrowsBadRequest() {
    MultipartFile bad = upload("{not-json");
    assertThrows(BadRequestException.class, () -> service.previewImport(SUB, bad));
  }

  @Test
  void preview_missingBlueprintsArrayThrowsBadRequest() {
    MultipartFile wrong = upload("{\"foo\":1}");
    assertThrows(BadRequestException.class, () -> service.previewImport(SUB, wrong));
  }

  // ------------------------------------------------------------------ apply --

  @Test
  void apply_addsRowAndLearnsAliasForManualPick() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // External name differs from the chosen key by normalization -> a manual resolution.
    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Calico Legs (Tactical)", "calico legs tactical", null, "from import")));

    assertEquals(1, result.added());
    assertEquals(1, result.aliasesLearned());
    assertEquals(0, result.skipped());
    verify(personalBlueprintRepository, times(1)).save(any());
    verify(aliasRepository, times(1)).save(any(BlueprintExternalAlias.class));
  }

  @Test
  void apply_doesNotLearnAliasWhenNameAlreadyNormalizesToKey() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Calico Legs Tactical", "calico legs tactical", null, null)));

    assertEquals(1, result.added());
    assertEquals(0, result.aliasesLearned());
    verify(aliasRepository, never()).save(any());
  }

  @Test
  void apply_skipsBlankAndUnresolvableKeys() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto("Skipped One", "  ", null, null),
                new BlueprintImportResolutionDto("Ghost", "no-such-key", null, null)));

    assertEquals(0, result.added());
    assertEquals(2, result.skipped());
    verify(personalBlueprintRepository, never()).save(any());
  }

  @Test
  void apply_countsAlreadyOwnedAndDedupesWithinRequest() {
    when(blueprintProductService.allProducts())
        .thenReturn(
            List.of(
                product("arclight pistol", "Arclight Pistol"),
                product("calico legs", "Calico Legs")));
    PersonalBlueprint owned =
        PersonalBlueprint.builder().ownerUserId(SUB).productKey("arclight pistol").build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto("Arclight Pistol", "arclight pistol", null, null),
                new BlueprintImportResolutionDto("Calico Legs", "calico legs", null, null),
                new BlueprintImportResolutionDto("Calico Legs Again", "calico legs", null, null)));

    assertEquals(1, result.added());
    assertEquals(2, result.alreadyOwned());
    verify(personalBlueprintRepository, times(1)).save(any());
  }

  @Test
  void apply_isBulkSafe_savesEachNewRowExactlyOnce() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("a", "A"), product("b", "B"), product("c", "C")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto("A", "a", null, null),
                new BlueprintImportResolutionDto("B", "b", null, null),
                new BlueprintImportResolutionDto("C", "c", null, null)));

    assertEquals(3, result.added());
    // No double save (no @Version re-bump from a detaching bulk update mid-loop).
    verify(personalBlueprintRepository, times(3)).save(any());
  }

  @Test
  void apply_doesNotDuplicateAliasWhenOneAlreadyExists() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(aliasRepository.findBySourceSystemAndExternalNameIgnoreCase(
            SCMDB, "Calico Legs (Tactical)"))
        .thenReturn(Optional.of(new BlueprintExternalAlias()));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Calico Legs (Tactical)", "calico legs tactical", null, null)));

    assertEquals(1, result.added());
    assertEquals(0, result.aliasesLearned());
    verify(aliasRepository, never()).save(any());
  }

  @Test
  void apply_doesNotDuplicateAliasWhenCaseVariantAlreadyExists() {
    // covers REQ-INV-020 — the pre-create alias guard folds case (matching the resolution lookup
    // and the LOWER(external_name) unique index), so a differently-cased stored alias suppresses a
    // new insert instead of producing a duplicate that would later break the Optional lookup.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    // A stored alias under a different casing is matched by the case-insensitive guard.
    when(aliasRepository.findBySourceSystemAndExternalNameIgnoreCase(
            SCMDB, "CALICO LEGS (TACTICAL)"))
        .thenReturn(Optional.of(new BlueprintExternalAlias()));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "CALICO LEGS (TACTICAL)", "calico legs tactical", null, null)));

    assertEquals(1, result.added());
    assertEquals(0, result.aliasesLearned());
    verify(aliasRepository, never()).save(any());
  }

  @Test
  void apply_suppressesCaseVariantDuplicateWithinSameRequest() {
    // covers REQ-INV-020 — two manual picks in one request that differ only in case must learn the
    // alias exactly once (the in-request seen-set folds case), never two rows for one folded name.
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("calico legs tactical", "Calico Legs Tactical")));
    when(personalBlueprintRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Calico Legs (Tactical)", "calico legs tactical", null, null),
                new BlueprintImportResolutionDto(
                    "CALICO LEGS (TACTICAL)", "calico legs tactical", null, null)));

    assertEquals(1, result.added());
    assertEquals(1, result.aliasesLearned());
    verify(aliasRepository, times(1)).save(any(BlueprintExternalAlias.class));
  }

  // ----------------------------------------------------- apply: re-import refresh --

  @Test
  void apply_refreshesAcquiredAtEarlierOnReimportWithoutDuplicating() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    Instant later = Instant.parse("2026-03-26T16:00:00Z");
    Instant earlier = Instant.parse("2025-01-01T00:00:00Z");
    PersonalBlueprint owned =
        PersonalBlueprint.builder()
            .ownerUserId(SUB)
            .productKey("arclight pistol")
            .acquiredAt(later)
            .build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Arclight Pistol", "arclight pistol", earlier, null)));

    assertEquals(0, result.added());
    assertEquals(1, result.alreadyOwned());
    assertEquals(1, result.acquiredAtUpdated());
    assertEquals(earlier, owned.getAcquiredAt());
    // No duplicate row: the already-owned entity is only mutated, never re-saved.
    verify(personalBlueprintRepository, never()).save(any());
  }

  @Test
  void apply_keepsAcquiredAtWhenReimportIsLater() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    Instant earlier = Instant.parse("2025-01-01T00:00:00Z");
    Instant later = Instant.parse("2026-03-26T16:00:00Z");
    PersonalBlueprint owned =
        PersonalBlueprint.builder()
            .ownerUserId(SUB)
            .productKey("arclight pistol")
            .acquiredAt(earlier)
            .build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Arclight Pistol", "arclight pistol", later, null)));

    assertEquals(earlier, owned.getAcquiredAt());
    assertEquals(0, result.acquiredAtUpdated());
  }

  @Test
  void apply_keepsAcquiredAtWhenReimportHasNoTimestamp() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    Instant existing = Instant.parse("2025-01-01T00:00:00Z");
    PersonalBlueprint owned =
        PersonalBlueprint.builder()
            .ownerUserId(SUB)
            .productKey("arclight pistol")
            .acquiredAt(existing)
            .build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Arclight Pistol", "arclight pistol", null, null)));

    assertEquals(existing, owned.getAcquiredAt());
    assertEquals(0, result.acquiredAtUpdated());
  }

  @Test
  void apply_fillsAcquiredAtWhenOwnedRowHasNone() {
    when(blueprintProductService.allProducts())
        .thenReturn(List.of(product("arclight pistol", "Arclight Pistol")));
    Instant incoming = Instant.parse("2025-01-01T00:00:00Z");
    PersonalBlueprint owned =
        PersonalBlueprint.builder().ownerUserId(SUB).productKey("arclight pistol").build();
    when(personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(eq(SUB), any()))
        .thenReturn(List.of(owned));

    BlueprintImportResultDto result =
        service.applyImport(
            SUB,
            List.of(
                new BlueprintImportResolutionDto(
                    "Arclight Pistol", "arclight pistol", incoming, null)));

    assertEquals(incoming, owned.getAcquiredAt());
    assertEquals(1, result.acquiredAtUpdated());
  }
}
