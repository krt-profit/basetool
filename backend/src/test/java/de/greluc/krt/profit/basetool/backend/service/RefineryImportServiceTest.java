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
import de.greluc.krt.profit.basetool.backend.mapper.MaterialCategoryMapperImpl;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapperImpl;
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
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
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
 * Unit tests of the refinery screenshot import draft: material matching, the skip, un-quoted and
 * checksum rules, and the order field mapping, with the real fuzzy matcher and mappers.
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
    lenient().when(userRepository.findPlainById(CALLER_ID)).thenReturn(Optional.of(caller));

    UserMapper userMapper = new UserMapperImpl();
    ReflectionTestUtils.setField(
        userMapper, "membershipRepository", Mockito.mock(OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        userMapper,
        "staffelMembershipResolver",
        new StaffelMembershipResolver(
            Mockito.mock(SquadronRepository.class),
            Mockito.mock(
                de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository.class)));

    service =
        new RefineryImportService(
            materialRepository,
            refiningMethodRepository,
            locationRepository,
            userRepository,
            aliasService,
            new BlueprintFuzzyMatcher(),
            BoundProperties.defaults(RefineryImportProperties.class),
            new MaterialMapperImpl(new MaterialCategoryMapperImpl()),
            Mappers.getMapper(LocationMapper.class),
            Mappers.getMapper(RefiningMethodMapper.class),
            userMapper);
  }

  @Test
  void buildDraft_rejectsUnsupportedSchemaVersion() {
    RefineryExtractDto extract = extract(2, setupOrder(List.of(quotedGood(0, "STILERON (ORE)"))));

    assertThatThrownBy(() -> service.buildDraft(extract, CALLER_ID))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.refineryImport.unsupportedSchemaVersion");
  }

  @Test
  void buildDraft_rejectsProcessingPanel() {
    RefineryExtractOrderDto order =
        new RefineryExtractOrderDto(
            "PROCESSING", true, 0.9, null, null, null, null, null, null, null, null, List.of());
    RefineryExtractDto extract = extract(1, order);

    assertThatThrownBy(() -> service.buildDraft(extract, CALLER_ID))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.refineryImport.unsupportedPanelType");
  }

  @Test
  void buildDraft_flagsMultipleOrdersAsTruncatedInfo() {
    RefineryExtractOrderDto first = setupOrder(List.of(quotedGood(0, "STILERON (ORE)")));
    RefineryExtractOrderDto second = setupOrder(List.of(quotedGood(0, "LINDINIUM (ORE)")));

    RefineryImportDraftDto draft = service.buildDraft(extract(1, first, second), CALLER_ID);

    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.MULTIPLE_ORDERS_TRUNCATED);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(draft.order().goods()).hasSize(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().name()).isEqualTo("Stileron (Raw)");
  }

  @Test
  void buildDraft_matchesOreSuffixAgainstRawSuffixViaCanonicalFold() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "STILERON (ORE)"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id()).isEqualTo(stileron.getId());
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.LOW_CONFIDENCE_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_matchesCaseInsensitively() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "stileron (raw)"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
  }

  @Test
  void buildDraft_matchesViaRefineryScreenAlias() {
    lenient()
        .when(
            aliasService.resolveMaterialByAlias(
                MaterialExternalAliasSource.REFINERY_SCREEN, "SHINY ROCKS"))
        .thenReturn(lindinium);

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "SHINY ROCKS"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id()).isEqualTo(lindinium.getId());
    verify(aliasService)
        .resolveMaterialByAlias(MaterialExternalAliasSource.REFINERY_SCREEN, "SHINY ROCKS");
  }

  @Test
  void buildDraft_ignoresAliasTargetingNonCandidateMaterial() {
    lenient()
        .when(
            aliasService.resolveMaterialByAlias(
                MaterialExternalAliasSource.REFINERY_SCREEN, "WEIRD STUFF"))
        .thenReturn(stileronRefined);

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "WEIRD STUFF"));

    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_matchesGameUiTruncatedNameViaUniqueSuffix() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionSalvage.getId());
  }

  @Test
  void buildDraft_matchesTruncatedNameViaAliasContainmentAnchor() {
    Material constructionMaterialSalvage =
        material("Construction Material Salvage", MaterialType.RAW, false);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum, constructionMaterialSalvage));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionMaterialSalvage)));

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionMaterialSalvage.getId());
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.LOW_CONFIDENCE_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_matchesBothSideTruncatedNameViaAliasContainmentAnchor() {
    Material constructionMaterialSalvage =
        material("Construction Material Salvage", MaterialType.RAW, false);
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum, constructionMaterialSalvage));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionMaterialSalvage)));

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALV"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionMaterialSalvage.getId());
  }

  @Test
  void buildDraft_countsNameAndAliasAnchorOfSameMaterialAsOneHit() {
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", constructionSalvage)));

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(constructionSalvage.getId());
  }

  @Test
  void buildDraft_leavesAmbiguousTruncationAcrossNameAndAliasAnchorsUnmatched() {
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

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_ignoresAliasAnchorTargetingNonCandidateMaterial() {
    lenient()
        .when(materialRepository.findRefineryInputCandidates(MaterialType.RAW))
        .thenReturn(List.of(stileron, lindinium, aluminum));
    lenient()
        .when(aliasService.findBySourceSystem(MaterialExternalAliasSource.REFINERY_SCREEN))
        .thenReturn(List.of(alias("CONSTRUCTION SALVAGE", stileronRefined)));

    RefineryImportDraftDto draft = draftFor(quotedGood(0, "UCTION SALVAGE"));

    assertThat(draft.goodsMatched()).isZero();
    assertThat(draft.order().goods().getFirst().inputMaterial()).isNull();
    assertThat(issues(draft, ImportIssueCode.UNMATCHED_MATERIAL)).hasSize(1);
  }

  @Test
  void buildDraft_matchesManualRawMaterialCandidate() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "QUANTAINIUM"));

    assertThat(draft.goodsMatched()).isEqualTo(1);
    assertThat(draft.order().goods().getFirst().inputMaterial().id())
        .isEqualTo(quantainium.getId());
    verify(materialRepository).findRefineryInputCandidates(MaterialType.RAW);
  }

  @Test
  void buildDraft_acceptsFuzzyMatchAboveThresholdButFlagsIt() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "LINDINIUMM (ORE)"));

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
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "ALUMINIUM (ORE)"));

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
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "XQZWV"));

    assertThat(draft.goodsMatched()).isZero();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNMATCHED_MATERIAL);
    assertThat(issue.suggestions()).isNull();
  }

  @Test
  void buildDraft_preservesDuplicateMaterialRowsInRowIndexOrder() {
    RefineryImportDraftDto draft =
        draftFor(
            good(3, "LINDINIUM (ORE)", 729, 200, 90, true),
            good(0, "LINDINIUM (ORE)", 385, 957, 448, true),
            good(2, "LINDINIUM (ORE)", 618, 500, 230, true),
            good(1, "LINDINIUM (ORE)", 585, 300, 140, true));

    assertThat(draft.goodsMatched()).isEqualTo(4);
    assertThat(draft.order().goods()).hasSize(4);
    assertThat(draft.order().goods().stream().map(g -> g.quality()).toList())
        .containsExactly(385, 585, 618, 729);
    assertThat(draft.order().goods())
        .allSatisfy(g -> assertThat(g.inputMaterial().id()).isEqualTo(lindinium.getId()));
  }

  @Test
  void buildDraft_skipsRefineOffRowAsInfo() {
    RefineryImportDraftDto draft =
        draftFor(quotedGood(0, "STILERON (ORE)"), good(1, "INERT MATERIALS", 0, 5449, 0, false));

    assertThat(draft.order().goods()).hasSize(1);
    assertThat(draft.rowsSkipped()).isEqualTo(1);
    assertThat(draft.goodsTotal()).isEqualTo(2);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.SKIPPED_REFINE_OFF);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(issue.field()).isEqualTo("goods[1]");
  }

  @Test
  void buildDraft_skipsZeroQuantityRowAsWarning() {
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", 618, 0, 5, true));

    assertThat(draft.order().goods()).isEmpty();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.SKIPPED_ZERO_QTY);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.WARNING);
  }

  @Test
  void buildDraft_reportsUnquotedRowDistinctFromZeroQty() {
    RefineryImportDraftDto draft =
        draftFor(good(0, "STILERON (ORE)", 618, 957, null, true), quotedGood(1, "LINDINIUM (ORE)"));

    assertThat(draft.order().goods()).hasSize(1);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNQUOTED_ROW);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(issues(draft, ImportIssueCode.SKIPPED_ZERO_QTY)).isEmpty();
    assertThat(issues(draft, ImportIssueCode.UNQUOTED_ORDER)).isEmpty();
  }

  @Test
  void buildDraft_flagsAllUnquotedRowsAsBlockingUnquotedOrder() {
    RefineryImportDraftDto draft =
        draftFor(
            good(0, "STILERON (ORE)", 618, 957, null, true),
            good(1, "LINDINIUM (ORE)", 385, 300, null, true));

    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.UNQUOTED_ORDER);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.BLOCKING);
    assertThat(draft.order().goods()).isEmpty();
    assertThat(draft.rowsSkipped()).isEqualTo(2);
  }

  @Test
  void buildDraft_honoursProducerQuotedFlagForUnquotedOrder() {
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

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    assertThat(issues(draft, ImportIssueCode.UNQUOTED_ORDER)).hasSize(1);
  }

  @Test
  void buildDraft_flagsSumMismatchWhenRefineOnRowsExceedToRefineTotal() {
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(9999L, 800L)), CALLER_ID);

    ImportIssueDto mismatch = onlyIssue(draft, ImportIssueCode.SUM_MISMATCH);
    assertThat(mismatch.field()).isEqualTo("rawToRefineTotal");
    assertThat(mismatch.rawValue()).isEqualTo("800 != 957");
  }

  @Test
  void buildDraft_flagsSumMismatchWhenSingleRowExceedsToRefineTotal() {
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(null, 955L)), CALLER_ID);

    assertThat(onlyIssue(draft, ImportIssueCode.SUM_MISMATCH).field())
        .isEqualTo("rawToRefineTotal");
  }

  @Test
  void buildDraft_acceptsToRefineShortfallFromScrolledOutRows() {
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(null, 5000L)), CALLER_ID);

    assertThat(issues(draft, ImportIssueCode.SUM_MISMATCH)).isEmpty();
  }

  @Test
  void buildDraft_ignoresInManifestTotalEvenWhenItExcludesInertRows() {
    RefineryImportDraftDto draft =
        service.buildDraft(extract(1, headerOrder(957L, 957L)), CALLER_ID);

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

  @Test
  void buildDraft_keepsOutOfRangeQualityButWarns() {
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", 1500, 957, 448, true));

    assertThat(draft.order().goods().getFirst().quality()).isEqualTo(1500);
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.OUT_OF_RANGE_QUALITY);
    assertThat(issue.field()).isEqualTo("goods[0].quality");
  }

  @Test
  void buildDraft_defaultsNullQualityToZero() {
    RefineryImportDraftDto draft = draftFor(good(0, "STILERON (ORE)", null, 957, 448, true));

    assertThat(draft.order().goods().getFirst().quality()).isZero();
    assertThat(issues(draft, ImportIssueCode.OUT_OF_RANGE_QUALITY)).isEmpty();
  }

  @Test
  void buildDraft_derivesOutputMaterialFromRefinedMaterialLink() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "STILERON (ORE)"));

    assertThat(draft.order().goods().getFirst().outputMaterial().id())
        .isEqualTo(stileronRefined.getId());
    assertThat(issues(draft, ImportIssueCode.NO_REFINED_MATERIAL)).isEmpty();
  }

  @Test
  void buildDraft_reportsMissingRefinedMaterialLinkAsInfo() {
    RefineryImportDraftDto draft = draftFor(quotedGood(0, "LINDINIUM (ORE)"));

    assertThat(draft.order().goods().getFirst().outputMaterial()).isNull();
    ImportIssueDto issue = onlyIssue(draft, ImportIssueCode.NO_REFINED_MATERIAL);
    assertThat(issue.severity()).isEqualTo(ImportIssueSeverity.INFO);
    assertThat(issue.field()).isEqualTo("goods[0].outputMaterial");
    assertThat(issue.confidence()).isEqualTo(0.95);
  }

  @Test
  void buildDraft_mapsOrderLevelFieldsAndDefaults() {
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

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

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

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    assertThat(draft.order().refiningMethod().id()).isEqualTo(dinyx.getId());
    assertThat(issues(draft, ImportIssueCode.UNRESOLVED_METHOD)).isEmpty();
  }

  @Test
  void buildDraft_derivesStartedAtFromTheLatestCapture() {
    RefineryExtractOrderDto order =
        setupOrder(
            List.of(quotedGood(0, "STILERON (ORE)")),
            List.of(
                image("a_upper.png", Instant.parse("2026-06-01T19:38:23Z")),
                image("b_lower.png", Instant.parse("2026-06-01T19:39:01Z")),
                image("c_pasted.png", null)));

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    assertThat(draft.order().startedAt()).isEqualTo(Instant.parse("2026-06-01T19:39:01Z"));
  }

  @Test
  void buildDraft_leavesStartedAtNullWhenNoCaptureTimeIsKnown() {
    RefineryExtractOrderDto order =
        setupOrder(List.of(quotedGood(0, "STILERON (ORE)")), List.of(image("a_upper.png", null)));

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    assertThat(draft.order().startedAt()).isNull();
  }

  @Test
  void buildDraft_flagsMissingLocationAndMethodForPreCroppedInput() {
    RefineryExtractOrderDto order = setupOrder(List.of(quotedGood(0, "STILERON (ORE)")));

    RefineryImportDraftDto draft = service.buildDraft(extract(1, order), CALLER_ID);

    assertThat(draft.order().location()).isNull();
    assertThat(draft.order().refiningMethod()).isNull();
    assertThat(onlyIssue(draft, ImportIssueCode.UNRESOLVED_LOCATION).severity())
        .isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(onlyIssue(draft, ImportIssueCode.UNRESOLVED_METHOD).severity())
        .isEqualTo(ImportIssueSeverity.WARNING);
  }

  @Test
  void buildDraft_leavesOwnerNullWhenCallerUnknown() {
    UUID strangerId = UUID.randomUUID();
    lenient().when(userRepository.findPlainById(strangerId)).thenReturn(Optional.empty());

    RefineryImportDraftDto draft =
        service.buildDraft(
            extract(1, setupOrder(List.of(quotedGood(0, "STILERON (ORE)")))), strangerId);

    assertThat(draft.order().owner()).isNull();
  }

  @Test
  void matchMethod_isCaseInsensitiveAndNullSafe() {
    assertThat(service.matchMethod("FERRON EXCHANGE")).contains(ferronExchange);
    assertThat(service.matchMethod(null)).isEmpty();
    assertThat(service.matchMethod("  ")).isEmpty();
  }

  @Test
  void matchMethod_snapsAMisReadToTheClosedEnumButRejectsNonMethodText() {
    RefiningMethod dinyx = refiningMethod("Dinyx Solventation");
    RefiningMethod electro = refiningMethod("Electrostarolysis");
    Mockito.when(refiningMethodRepository.findAll())
        .thenReturn(List.of(dinyx, electro, ferronExchange));

    assertThat(service.matchMethod("DINYX SOLVATION")).contains(dinyx);
    assertThat(service.matchMethod("ferron-exchange")).contains(ferronExchange);
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
    Location levskiDuplicate = new Location();
    levskiDuplicate.setId(UUID.randomUUID());
    levskiDuplicate.setName("LEVSKI ");
    Mockito.when(locationRepository.findLocationsWithRefinery())
        .thenReturn(List.of(levski, levskiDuplicate));

    assertThat(service.matchRefineryLocation("LEVSKI")).isEmpty();
  }

  @Test
  void matchMaterial_exposesTheMatchingChain() {
    assertThat(service.matchMaterial("STILERON (ORE)")).contains(stileron);
    assertThat(service.matchMaterial("XQZWV")).isEmpty();
  }

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
