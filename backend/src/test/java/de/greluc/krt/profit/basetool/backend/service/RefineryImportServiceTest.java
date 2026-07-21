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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.config.RefineryImportProperties;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.LocationMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.RefiningMethodMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapperImpl;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractImageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests of the refinery screenshot import draft building (#434): the §7.3 material-matching
 * stages, the §5 skip / un-quoted / checksum rules and the order-level field mapping — all against
 * mocked repositories with the real fuzzy matcher and real MapStruct mappers.
 */
@ExtendWith(MockitoExtension.class)
class RefineryImportServiceTest {

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Mock private MaterialRepository materialRepository;
  @Mock private RefiningMethodRepository refiningMethodRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialExternalAliasService aliasService;

  private RefineryImportService service;

  private Material stileron;
  private Material stileronRefined;
  private Material lindinium;
  private Material aluminum;
  private Material constructionSalvage;
  private Material quantainium;
  private Location levski;
  private RefiningMethod ferronExchange;

  @BeforeEach
  void setUp() {
    stileronRefined = material("Stileron", MaterialType.REFINED, false);
    stileron = material("Stileron (Raw)", MaterialType.RAW, false);
    stileron.setRefinedMaterial(stileronRefined);
    lindinium = material("Lindinium (Raw)", MaterialType.RAW, false);
    aluminum = material("Aluminum (Raw)", MaterialType.RAW, false);
    constructionSalvage = material("Construction Salvage", MaterialType.RAW, false);
    quantainium = material("Quantainium", MaterialType.NO_REFINE, true);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum, constructionSalvage, quantainium));

    levski = new Location();
    levski.setId(UUID.randomUUID());
    levski.setName("Levski");
    lenient().when(locationRepository.findLocationsWithRefinery()).thenReturn(List.of(levski));

    ferronExchange = new RefiningMethod();
    ferronExchange.setId(UUID.randomUUID());
    ferronExchange.setName("Ferron Exchange");
    lenient()
        .when(refiningMethodRepository.findByNameIgnoreCase("FERRON EXCHANGE"))
        .thenReturn(Optional.of(ferronExchange));

    lenient()
        .when(
            aliasService.resolveMaterialByAlias(
                Mockito.any(MaterialExternalAliasSource.class), Mockito.anyString()))
        .thenReturn(null);

    User caller = new User();
    caller.setId(CALLER_ID);
    caller.setUsername("uploader");
    caller.setRank(10);
    lenient().when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(caller));

    UserMapper userMapper = new UserMapperImpl();
    ReflectionTestUtils.setField(
        userMapper, "membershipRepository", Mockito.mock(OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        userMapper,
        "staffelMembershipResolver",
        new StaffelMembershipResolver(Mockito.mock(SquadronRepository.class)));

    service =
        new RefineryImportService(
            materialRepository,
            refiningMethodRepository,
            locationRepository,
            userRepository,
            aliasService,
            new BlueprintFuzzyMatcher(),
            new RefineryImportProperties(),
            Mappers.getMapper(MaterialMapper.class),
            Mappers.getMapper(LocationMapper.class),
            Mappers.getMapper(RefiningMethodMapper.class),
            userMapper);
  }

  // ─── envelope validation ──────────────────────────────────────────────────

  @Test
  void buildDraft_rejectsUnsupportedSchemaVersion() {
    // Given
    RefineryExtractDto extract = extract(2, setupOrder(List.of(quotedGood(0, "STILERON (ORE)"))));

    // When / Then
    assertThatThrownBy(() -> service.buildDraft(extract, CALLER_ID))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.refineryImport.unsupportedSchemaVersion");
  }

  @Test
  void buildDraft_rejectsProcessingPanel() {
    // Given
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "PROCESSING", true, 0.9, null, null, null, null, null, null, null, null, List.of());
    RefineryExtractDto extract = extract(1, order);

    // When / Then
    assertThatThrownBy(() -> service.buildDraft(extract, CALLER_ID))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.refineryImport.unsupportedPanelType");
  }

  @Test
  void buildDraft_flagsMultipleOrdersAsTruncatedInfo() {
    // Given
    RefineryExtractOrderDto first = setupOrder(List.of(quotedGood(0, "STILERON (ORE)")));
    RefineryExtractOrderDto second = setupOrder(List.of(quotedGood(0, "LINDINIUM (ORE)")));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, first, second), CALLER_ID);

    // Then
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.MULTIPLE_ORDERS_TRUNCATED);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(draft.order().goods()).hasSize(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().name()).isEqualTo("Stileron (Raw)");
  }

  // ─── material matching stages (§7.3) ──────────────────────────────────────

  @Test
  void buildDraft_matchesOreSuffixAgainstRawSuffixViaCanonicalFold() {
    // Given — master data says "Stileron (Raw)", the screen says "STILERON (ORE)"
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "STILERON (ORE)"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id()).isEqualTo(stileron.getId());
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.LOW_CONFIDENCE_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_matchesCaseInsensitively() {
    // Given
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "stileron (raw)"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
  }

  @Test
  void buildDraft_matchesViaRefineryScreenAlias() {
    // Given — a name no canonical fold can place, but an admin curated an alias for it
    lenient()
        .when(
            aliasService.resolveMaterialByAlias(
                MaterialExternalAliasSource.REFINERY_SCREEN, "SHINY ROCKS"))
        .thenReturn(lindinium);

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "SHINY ROCKS"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id()).isEqualTo(lindinium.getId());
    verify(aliasService)
        .resolveMaterialByAlias(MaterialExternalAliasSource.REFINERY_SCREEN, "SHINY ROCKS");
  }

  @Test
  void buildDraft_ignoresAliasTargetingNonCandidateMaterial() {
    // Given — an admin mis-curated an alias onto a REFINED material the create path rejects
    lenient()
        .when(
            aliasService.resolveMaterialByAlias(
                MaterialExternalAliasSource.REFINERY_SCREEN, "WEIRD STUFF"))
        .thenReturn(stileronRefined);

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "WEIRD STUFF"));

    // Then — the alias stage must not bypass the candidate gate; the row stays unmatched
    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_matchesGameUiTruncatedNameViaUniqueSuffix() {
    // Given — the game UI clipped "…Construction Salvage" to "UCTION SALVAGE" (golden set)
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionSalvage.getId());
  }

  @Test
  void buildDraft_matchesTruncatedNameViaAliasContainmentAnchor() {
    // Given — the catalogue names it "Construction Material Salvage" (UEX spelling), the game UI
    // shows "Construction Salvage" and clips it to "UCTION SALVAGE"; the master name does not
    // contain the fragment, but one curated alias of the on-screen spelling does
    Material constructionMaterialSalvage =
        material("Construction Material Salvage", MaterialType.RAW, false);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum, constructionMaterialSalvage));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionMaterialSalvage)));

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    // Then — a deterministic hit, not a fuzzy one
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionMaterialSalvage.getId());
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.LOW_CONFIDENCE_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_matchesBothSideTruncatedNameViaAliasContainmentAnchor() {
    // Given — clipping on both ends still yields a contiguous fragment of the alias name
    Material constructionMaterialSalvage =
        material("Construction Material Salvage", MaterialType.RAW, false);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum, constructionMaterialSalvage));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionMaterialSalvage)));

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALV"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionMaterialSalvage.getId());
  }

  @Test
  void buildDraft_countsNameAndAliasAnchorOfSameMaterialAsOneHit() {
    // Given — the fragment is contained in the candidate's own name AND in an alias pointing at
    // the same material; uniqueness is judged per material, so this stays a single hit
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionSalvage)));

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionSalvage.getId());
  }

  @Test
  void buildDraft_leavesAmbiguousTruncationAcrossNameAndAliasAnchorsUnmatched() {
    // Given — the fragment hits the candidate "Construction Salvage" directly AND an alias
    // pointing at a different material; the union has two materials, so no deterministic match
    Material constructionMaterialSalvage =
        material("Construction Material Salvage", MaterialType.RAW, false);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(
            List.of(
                stileron, lindinium, aluminum, constructionSalvage, constructionMaterialSalvage));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionMaterialSalvage)));

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    // Then — falls through to fuzzy, which stays below the accept threshold here
    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_ignoresAliasAnchorTargetingNonCandidateMaterial() {
    // Given — the only containment anchor is an alias mis-curated onto a REFINED material; the
    // truncation stage must honour the create-path gate just like the exact-alias stage
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", stileronRefined)));

    // When
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    // Then
    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_matchesManualRawMaterialCandidate() {
    // Given — isManualRawMaterial materials pass the create-path gate even when not type RAW
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "QUANTAINIUM"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(quantainium.getId());
    verify(materialRepository).findRefineryInputCandidates(MaterialType.RAW);
  }

  @Test
  void buildDraft_acceptsFuzzyMatchAboveThresholdButFlagsIt() {
    // Given — one-character drift on a ten-character name scores exactly 0.9
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "LINDINIUMM (ORE)"));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id()).isEqualTo(lindinium.getId());
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.LOW_CONFIDENCE_MATERIAL);
    assertThat(issue.field()).isEqualTo("goods[0].inputMaterial");
    assertThat(issue.confidence()).isEqualTo(0.9);
    assertThat(issue.suggestions()).isNotEmpty();
    assertThat(issue.suggestions().getFirst().id()).isEqualTo(lindinium.getId());
  }

  @Test
  void buildDraft_leavesFuzzyBelowThresholdUnmatchedWithSuggestions() {
    // Given — "ALUMINIUM" vs "Aluminum" scores ~0.889, below the 0.9 accept threshold
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "ALUMINIUM (ORE)"));

    // Then
    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNMATCHED_MATERIAL);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(issue.rawValue()).isEqualTo("ALUMINIUM (ORE)");
    assertThat(issue.suggestions()).isNotEmpty();
    assertThat(issue.suggestions().getFirst().id()).isEqualTo(aluminum.getId());
  }

  @Test
  void buildDraft_leavesGarbageUnmatchedWithoutSuggestions() {
    // Given
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "XQZWV"));

    // Then
    assertThat(draft.goodsMatched()).isZero();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNMATCHED_MATERIAL);
    assertThat(issue.suggestions()).isNull();
  }

  @Test
  void buildDraft_preservesDuplicateMaterialRowsInRowIndexOrder() {
    // Given — 4× LINDINIUM at different qualities is a normal real-world order (golden set)
    RefineryImportDraftDto draft =
        draftFor(
            good(3, "LINDINIUM (ORE)", 729, 200, 90, true),
            good(0, "LINDINIUM (ORE)", 385, 957, 448, true),
            good(2, "LINDINIUM (ORE)", 618, 500, 230, true),
            good(1, "LINDINIUM (ORE)", 585, 300, 140, true));

    // Then
    assertThat(draft.goodsMatched()).isEqualTo(4);
    assertThat(draft.order().goods()).hasSize(4);
    assertThat(draft.order().goods().stream().map(g -> g.quality()).toList())
        .containsExactly(385, 585, 618, 729);
    assertThat(draft.order().goods())
        .allSatisfy(g -> assertThat(g.inputMaterial().id()).isEqualTo(lindinium.getId()));
  }

  // ─── skip rules & un-quoted handling (§5) ─────────────────────────────────

  @Test
  void buildDraft_skipsRefineOffRowAsInfo() {
    // Given — INERT MATERIALS row: refine OFF
    RefineryImportDraftDto draft =
        draftFor(quotedGood(0, "STILERON (ORE)"), good(1, "INERT MATERIALS", 0, 5449, 0, false));

    // Then
    assertThat(draft.order().goods()).hasSize(1);
    assertThat(draft.rowsSkipped()).isEqualTo(1);
    assertThat(draft.goodsTotal()).isEqualTo(2);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.SKIPPED_REFINE_OFF);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(issue.field()).isEqualTo("goods[1]");
  }

  @Test
  void buildDraft_skipsZeroQuantityRowAsWarning() {
    // Given
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", 618, 0, 5, true));

    // Then
    assertThat(draft.order().goods()).isEmpty();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.SKIPPED_ZERO_QTY);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.WARNING);
  }

  @Test
  void buildDraft_reportsUnquotedRowDistinctFromZeroQty() {
    // Given — YIELD read as "--" (null) on one row, the other is quoted
    RefineryImportDraftDto draft =
        draftFor(good(0, "STILERON (ORE)", 618, 957, null, true), quotedGood(1, "LINDINIUM (ORE)"));

    // Then
    assertThat(draft.order().goods()).hasSize(1);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNQUOTED_ROW);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(issues(draft, ImportIssueCode.SKIPPED_ZERO_QTY)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.UNQUOTED_ORDER)).isEmpty();
  }

  @Test
  void buildDraft_flagsAllUnquotedRowsAsBlockingUnquotedOrder() {
    // Given — every YIELD cell is "--": captured before GET QUOTE
    RefineryImportDraftDto draft =
        draftFor(
            good(0, "STILERON (ORE)", 618, 957, null, true),
            good(1, "LINDINIUM (ORE)", 385, 300, null, true));

    // Then
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNQUOTED_ORDER);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.BLOCKING);
    assertThat(draft.order().goods()).isEmpty();
    assertThat(draft.rowsSkipped()).isEqualTo(2);
  }

  @Test
  void buildDraft_honoursProducerQuotedFlagForUnquotedOrder() {
    // Given — the producer marked the capture un-quoted even though rows carry numbers
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "SETUP",
            false,
            0.9,
            "LEVSKI",
            "FERRON EXCHANGE",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(quotedGood(0, "STILERON (ORE)")));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then
    assertThat(issues(draft, ImportIssueCode.UNQUOTED_ORDER)).hasSize(1);
  }

  // ─── header-total checksum (§5) ───────────────────────────────────────────

  @Test
  void buildDraft_flagsSumMismatchWhenRefineOnRowsExceedToRefineTotal() {
    // Given — refine-ON qty 957 vs TO REFINE 800: exceeds the header beyond the ±1-per-row
    // tolerance (2 rows) — a mis-read quantity or a duplicated capture
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(9999L, 800L)), CALLER_ID);

    // Then
    ImportIssueDto mismatch = onlyIssue(draft, ImportIssueCode.SUM_MISMATCH);
    assertThat(mismatch.field()).isEqualTo("rawToRefineTotal");
    assertThat(mismatch.rawValue()).isEqualTo("800 != 957");
  }

  @Test
  void buildDraft_flagsSumMismatchWhenSingleRowExceedsToRefineTotal() {
    // Given — TO REFINE 955: the sum (957) stays within the 2-row tolerance, but the single
    // refine-ON row (957) alone exceeds the header by more than 1
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(null, 955L)), CALLER_ID);

    // Then
    assertThat(onlyIssue(draft, ImportIssueCode.SUM_MISMATCH).field())
        .isEqualTo("rawToRefineTotal");
  }

  @Test
  void buildDraft_acceptsToRefineShortfallFromScrolledOutRows() {
    // Given — TO REFINE 5000 vs visible refine-ON qty 957: the list is a scrolling viewport,
    // a shortfall means scrolled-out rows and is never flagged (one-sided check)
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(null, 5000L)), CALLER_ID);

    // Then
    assertThat(issues(draft, ImportIssueCode.SUM_MISMATCH)).isEmpty();
  }

  @Test
  void buildDraft_ignoresInManifestTotalEvenWhenItExcludesInertRows() {
    // Given — the 2026-06 field sample shape: IN MANIFEST (957) excludes the inert row, the
    // all-row sum is 6406; IN MANIFEST is never validated, TO REFINE reconciles exactly
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(957L, 957L)), CALLER_ID);

    // Then
    assertThat(issues(draft, ImportIssueCode.SUM_MISMATCH)).isEmpty();
  }

  /** Order with one refine-ON row (qty 957) + the inert refine-OFF row (qty 5449). */
  private static RefineryExtractOrderDto headerOrder(Long inManifestTotal, Long toRefineTotal) {
    return new RefineryExtractOrderDto(
        "SETUP",
        true,
        0.9,
        "LEVSKI",
        "FERRON EXCHANGE",
        inManifestTotal,
        toRefineTotal,
        null,
        null,
        null,
        null,
        List.of(quotedGood(0, "STILERON (ORE)"), good(1, "INERT MATERIALS", 0, 5449, 0, false)));
  }

  // ─── per-good field mapping (§7.2) ────────────────────────────────────────

  @Test
  void buildDraft_keepsOutOfRangeQualityButWarns() {
    // Given
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", 1500, 957, 448, true));

    // Then
    assertThat(draft.order().goods().getFirst().quality()).isEqualTo(1500);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.OUT_OF_RANGE_QUALITY);
    assertThat(issue.field()).isEqualTo("goods[0].quality");
  }

  @Test
  void buildDraft_defaultsNullQualityToZero() {
    // Given
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", null, 957, 448, true));

    // Then
    assertThat(draft.order().goods().getFirst().quality()).isZero();
    assertThat(issues(draft, ImportIssueCode.OUT_OF_RANGE_QUALITY)).isEmpty();
  }

  @Test
  void buildDraft_derivesOutputMaterialFromRefinedMaterialLink() {
    // Given
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "STILERON (ORE)"));

    // Then
    assertThat(draft.order().goods().getFirst().outputMaterial().id())
        .isEqualTo(stileronRefined.getId());
    assertThat(issues(draft, ImportIssueCode.NO_REFINED_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_reportsMissingRefinedMaterialLinkAsInfo() {
    // Given — Lindinium has no admin-curated refinedMaterial
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "LINDINIUM (ORE)"));

    // Then
    assertThat(draft.order().goods().getFirst().outputMaterial()).isNull();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.NO_REFINED_MATERIAL);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(issue.field()).isEqualTo("goods[0].outputMaterial");
    // row-level issues carry the row's derived read confidence (REQ-REFINERY-009)
    assertThat(issue.confidence()).isEqualTo(0.95);
  }

  // ─── order-level mapping (§7.1) ───────────────────────────────────────────

  @Test
  void buildDraft_mapsOrderLevelFieldsAndDefaults() {
    // Given
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "SETUP",
            true,
            0.92,
            "LEVSKI",
            "FERRON EXCHANGE",
            null,
            null,
            48928.0,
            1258L,
            null,
            null,
            List.of(quotedGood(0, "STILERON (ORE)")));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then
    assertThat(draft.order().location().id()).isEqualTo(levski.getId());
    assertThat(draft.order().refiningMethod().id()).isEqualTo(ferronExchange.getId());
    assertThat(draft.order().expenses()).isEqualTo(48928.0);
    assertThat(draft.order().durationMinutes()).isEqualTo(1258L);
    assertThat(draft.order().status()).isEqualTo(RefineryOrderStatus.OPEN.name());
    assertThat(draft.order().owner().id()).isEqualTo(CALLER_ID);
    assertThat(draft.order().mission()).isNull();
    assertThat(draft.order().startedAt()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNRESOLVED_LOCATION)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.UNRESOLVED_METHOD)).isEmpty();
  }

  @Test
  void buildDraft_recoversTheVlmMethodAutocorrect() {
    // Given — the terminal read "DINYX SOLVATION", the VLM's autocorrect of the game's
    // "DINYX SOLVENTATION"; the closed-enum nearest-match must resolve it, not flag it unresolved.
    RefiningMethod dinyx = refiningMethod("Dinyx Solventation");
    Mockito.when(refiningMethodRepository.findAll()).thenReturn(List.of(dinyx, ferronExchange));
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "SETUP",
            true,
            0.92,
            "LEVSKI",
            "DINYX SOLVATION",
            null,
            null,
            48928.0,
            1258L,
            null,
            null,
            List.of(quotedGood(0, "STILERON (ORE)")));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then
    assertThat(draft.order().refiningMethod().id()).isEqualTo(dinyx.getId());
    assertThat(issues(draft, ImportIssueCode.UNRESOLVED_METHOD)).isEmpty();
  }

  @Test
  void buildDraft_derivesStartedAtFromTheLatestCapture() {
    // Given — a scrolled three-capture order; the LAST capture marks the order start
    // (REQ-REFINERY-017), and an image without a capture time must not disturb the max
    RefineryExtractOrderDto order =
        setupOrder(
            List.of(quotedGood(0, "STILERON (ORE)")),
            List.of(
                image("a_upper.png", Instant.parse("2026-06-01T19:38:23Z")),
                image("b_lower.png", Instant.parse("2026-06-01T19:39:01Z")),
                image("c_pasted.png", null)));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then
    assertThat(draft.order().startedAt()).isEqualTo(Instant.parse("2026-06-01T19:39:01Z"));
  }

  @Test
  void buildDraft_leavesStartedAtNullWhenNoCaptureTimeIsKnown() {
    // Given — an older extractor: source images without the additive capturedAt field
    RefineryExtractOrderDto order =
        setupOrder(List.of(quotedGood(0, "STILERON (ORE)")), List.of(image("a_upper.png", null)));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then — the create flow keeps its "now" default at save time
    assertThat(draft.order().startedAt()).isNull();
  }

  @Test
  void buildDraft_flagsMissingLocationAndMethodForPreCroppedInput() {
    // Given — pre-cropped panel input never contains the terminal header
    RefineryExtractOrderDto order = setupOrder(List.of(quotedGood(0, "STILERON (ORE)")));

    // When
    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    // Then
    assertThat(draft.order().location()).isNull();
    assertThat(draft.order().refiningMethod()).isNull();
    assertThat(onlyIssue(draft, ImportIssueCode.UNRESOLVED_LOCATION).severity())
        .isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(onlyIssue(draft, ImportIssueCode.UNRESOLVED_METHOD).severity())
        .isEqualTo(ImportIssueSeverity.WARNING);
  }

  @Test
  void buildDraft_leavesOwnerNullWhenCallerUnknown() {
    // Given
    UUID strangerId = UUID.randomUUID();
    lenient().when(userRepository.findById(strangerId)).thenReturn(Optional.empty());

    // When
    RefineryImportDraftDto draft =
        service.buildDraft(
            extract(1, setupOrder(List.of(quotedGood(0, "STILERON (ORE)")))), strangerId);

    // Then
    assertThat(draft.order().owner()).isNull();
  }

  // ─── standalone matchers ──────────────────────────────────────────────────

  @Test
  void matchMethod_isCaseInsensitiveAndNullSafe() {
    assertThat(service.matchMethod("FERRON EXCHANGE")).contains(ferronExchange);
    assertThat(service.matchMethod(null)).isEmpty();
    assertThat(service.matchMethod("  ")).isEmpty();
  }

  @Test
  void matchMethod_snapsAMisReadToTheClosedEnumButRejectsNonMethodText() {
    // The VLM autocorrects the game's "DINYX SOLVENTATION" to the real word and emits
    // "DINYX SOLVATION" (PHASE0 method taxonomy); no exact lookup resolves it, so the closed-enum
    // nearest-match must recover the intended method.
    RefiningMethod dinyx = refiningMethod("Dinyx Solventation");
    RefiningMethod electro = refiningMethod("Electrostarolysis");
    Mockito.when(refiningMethodRepository.findAll())
        .thenReturn(List.of(dinyx, electro, ferronExchange));

    // Stage 3 (fuzzy nearest-match over the nine distinct methods) recovers the autocorrect.
    assertThat(service.matchMethod("DINYX SOLVATION")).contains(dinyx);
    // Stage 2 (canonical fold) resolves spacing / punctuation drift before the fuzzy stage.
    assertThat(service.matchMethod("ferron-exchange")).contains(ferronExchange);
    // Non-method panel text peaks below the accept threshold and stays unresolved (no wrong snap).
    assertThat(service.matchMethod("PROCESSING SELECTION")).isEmpty();
  }

  @Test
  void matchRefineryLocation_matchesByCanonicalFold() {
    assertThat(service.matchRefineryLocation("LEVSKI")).contains(levski);
    assertThat(service.matchRefineryLocation("Orison")).isEmpty();
    assertThat(service.matchRefineryLocation(null)).isEmpty();
  }

  @Test
  void matchRefineryLocation_returnsEmptyWhenMultipleCandidatesShareTheFold() {
    // Given — two refinery-equipped locations whose names both fold to the canonical core
    // "levski" ("Levski" and "LEVSKI " differ only by case and trailing space)
    Location levskiDuplicate = new Location();
    levskiDuplicate.setId(UUID.randomUUID());
    levskiDuplicate.setName("LEVSKI ");
    Mockito.when(locationRepository.findLocationsWithRefinery())
        .thenReturn(List.of(levski, levskiDuplicate));

    // When / Then — ambiguity (hits.size() != 1) yields no match rather than an arbitrary
    // first-hit guess, so the draft is left unresolved for the user to correct
    assertThat(service.matchRefineryLocation("LEVSKI")).isEmpty();
  }

  @Test
  void matchMaterial_exposesTheMatchingChain() {
    assertThat(service.matchMaterial("STILERON (ORE)")).contains(stileron);
    assertThat(service.matchMaterial("XQZWV")).isEmpty();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private RefineryImportDraftDto draftFor(RefineryExtractGoodDto... goods) {
    return service.buildDraft(extract(1, setupOrder(List.of(goods))), CALLER_ID);
  }

  private static RefineryExtractDto extract(int schemaVersion, RefineryExtractOrderDto... orders) {
    return new RefineryExtractDto(
        schemaVersion,
        "basetool-sc-extractor",
        "1.0.0",
        "qwen3-vl:8b-instruct",
        Instant.parse("2026-06-05T20:00:00Z"),
        "en",
        List.of(orders));
  }

  private static RefineryExtractOrderDto setupOrder(List<RefineryExtractGoodDto> goods) {
    return setupOrder(goods, null);
  }

  private static RefineryExtractOrderDto setupOrder(
      List<RefineryExtractGoodDto> goods, List<RefineryExtractImageDto> sourceImages) {
    return new RefineryExtractOrderDto(
        "SETUP", true, 0.92, null, null, null, null, null, null, null, sourceImages, goods);
  }

  private static RefineryExtractImageDto image(String name, Instant capturedAt) {
    return new RefineryExtractImageDto(name, 1920, 1080, "vlm", capturedAt);
  }

  private static RefiningMethod refiningMethod(String name) {
    RefiningMethod method = new RefiningMethod();
    method.setId(UUID.randomUUID());
    method.setName(name);
    return method;
  }

  private static RefineryExtractGoodDto quotedGood(int rowIndex, String rawMaterialName) {
    return good(rowIndex, rawMaterialName, 618, 957, 448, true);
  }

  private static RefineryExtractGoodDto good(
      Integer rowIndex,
      String rawMaterialName,
      Integer quality,
      Integer inputQuantity,
      Integer outputQuantity,
      boolean refine) {
    return new RefineryExtractGoodDto(
        rowIndex, rawMaterialName, quality, inputQuantity, outputQuantity, refine, 0.95, null);
  }

  private static MaterialExternalAlias alias(String externalName, Material target) {
    MaterialExternalAlias alias = new MaterialExternalAlias();
    alias.setId(UUID.randomUUID());
    alias.setSourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN);
    alias.setExternalName(externalName);
    alias.setMaterial(target);
    return alias;
  }

  private static Material material(String name, MaterialType type, boolean manualRaw) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setType(type);
    material.setIsManualRawMaterial(manualRaw);
    material.setIsVisible(true);
    return material;
  }

  private static List<ImportIssueDto> issues(RefineryImportDraftDto draft, ImportIssueCode code) {
    return draft.issues().stream().filter(i -> i.code() == code).toList();
  }

  private static ImportIssueDto onlyIssue(RefineryImportDraftDto draft, ImportIssueCode code) {
    List<ImportIssueDto> matching = issues(draft, code);
    assertThat(matching).hasSize(1);
    return matching.getFirst();
  }
}
