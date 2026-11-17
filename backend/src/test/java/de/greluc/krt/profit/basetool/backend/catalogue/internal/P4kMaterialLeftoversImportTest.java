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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kCommodityFixtures.BORASE;
import static de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kCommodityFixtures.BORASE_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * An APPLY retires the leftover materials nothing references: a seeded commodity no rule would
 * create hands its identity to its twin or hides itself, an admin twin holding the P4K name gives
 * way, and an invisible Wiki-only twin hands its Wiki UUID to the material an alias names
 * (REQ-P4K-076…078).
 */
@SpringBootTest
@Transactional
class P4kMaterialLeftoversImportTest {

  private static final UUID SALVAGE_WIKI = UUID.fromString("0f000000-0000-4000-8000-0000000c2001");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private MaterialExternalAliasRepository aliasRepository;
  @Autowired private P4kReviewCaseRepository reviewCaseRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private AuditRecorder auditRecorder;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private JdbcTemplate jdbc;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void aSeededCommodityWhoseWikiKeyNamesATwin_handsItsIdentityOverAndIsDeleted() {
    Material seeded = seededBorase();
    Material twin = boraseTwin();
    UUID seededId = seeded.getId();
    Instant before = Instant.now().minusSeconds(1);

    List<P4kImportChange> changes = apply();

    assertThat(materialRepository.findById(seededId)).isEmpty();
    assertThat(twin.getP4kUuid()).isEqualTo(BORASE);
    assertThat(twin.getP4kSubtypeUuid()).isEqualTo(BORASE_TYPE);
    assertThat(twin.getName()).isEqualTo("Borase");
    assertThat(openCase(BORASE, P4kReviewReason.SEEDED_NOT_CREATABLE)).isFalse();
    assertThat(openCase(BORASE, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(changes)
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.MERGED
                && BORASE.equals(change.getGuid())
                && "Borase".equals(change.getOldValue())
                && "Borase Wiki Twin".equals(change.getNewValue()));
    assertThat(retiredSince(seededId, before)).isTrue();
  }

  @Test
  void aSeededCommodityACatalogueRowReferences_handsItsIdentityOverAndIsHidden() {
    Material seeded = seededBorase();
    Material twin = boraseTwin();
    Material ore = material("Borase Ore", MaterialSourceSystem.UEX_ONLY);
    ore.setRefinedMaterial(seeded);

    apply();

    assertThat(seeded.getP4kHiddenAt()).isNotNull();
    assertThat(seeded.getP4kUuid()).isNull();
    assertThat(seeded.getName()).isEqualTo("Borase (" + BORASE + ")");
    assertThat(ore.getRefinedMaterial()).isSameAs(seeded);
    assertThat(twin.getP4kUuid()).isEqualTo(BORASE);
    assertThat(twin.getName()).isEqualTo("Borase");
    assertThat(openCase(BORASE, P4kReviewReason.SEEDED_NOT_CREATABLE)).isFalse();
  }

  @Test
  void aSeededCommodityWithoutATwin_isHidden() {
    Material seeded = seededBorase();
    Instant before = Instant.now().minusSeconds(1);

    List<P4kImportChange> changes = apply();

    assertThat(seeded.getP4kHiddenAt()).isNotNull();
    assertThat(seeded.getP4kUuid()).isEqualTo(BORASE);
    assertThat(openCase(BORASE, P4kReviewReason.SEEDED_NOT_CREATABLE)).isFalse();
    assertThat(changes)
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.CHANGED
                && BORASE.equals(change.getGuid())
                && P4kMaterialRetirement.HIDDEN_COLUMN.equals(change.getFieldName()));
    assertThat(retiredSince(seeded.getId(), before)).isTrue();
  }

  @Test
  void aSeededCommodityAMemberReferences_staysACase() {
    Material seeded = seededBorase();
    Material twin = boraseTwin();
    materialRepository.flush();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, requested_material_id,"
            + " requested_amount, owner_id, status, posted_at, created_at, updated_at)"
            + " VALUES (?, 'MATERIAL', ?, 1, ?, 'ACTIVE', now(), now(), now())",
        UUID.randomUUID(),
        seeded.getId(),
        member());

    apply();

    assertThat(seeded.getP4kHiddenAt()).isNull();
    assertThat(seeded.getP4kUuid()).isEqualTo(BORASE);
    assertThat(twin.getP4kUuid()).isNull();
    assertThat(openCase(BORASE, P4kReviewReason.SEEDED_NOT_CREATABLE)).isTrue();
  }

  @Test
  void aPreviewReportsTheHandover_andWritesNothing() {
    Material seeded = seededBorase();
    Material twin = boraseTwin();

    List<P4kImportChange> changes =
        runs.previewWithReport(P4kTestCatalogues.fixtureBytes()).changes();

    assertThat(seeded.getP4kUuid()).isEqualTo(BORASE);
    assertThat(twin.getP4kUuid()).isNull();
    assertThat(materialRepository.findById(seeded.getId())).isPresent();
    assertThat(changes)
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.MERGED && BORASE.equals(change.getGuid()));
  }

  @Test
  void anAdminTwinHoldingTheP4kName_isDeleted_andTheMatchedMaterialTakesTheName() {
    Material raw = material("Aslarite (Raw)", MaterialSourceSystem.BOTH);
    raw.setP4kUuid(P4kTestCatalogues.ASLARITE);
    Material manual = adminTwin("Aslarite");
    alias(MaterialExternalAliasSource.SCWIKI, "Aslarite", raw);
    UUID manualId = manual.getId();
    Instant before = Instant.now().minusSeconds(1);

    apply();

    assertThat(materialRepository.findById(manualId)).isEmpty();
    assertThat(raw.getName()).isEqualTo("Aslarite");
    assertThat(openCase(P4kTestCatalogues.ASLARITE, P4kReviewReason.GUID_CONFLICT)).isFalse();
    assertThat(retiredSince(manualId, before)).isTrue();
  }

  @Test
  void anAdminTwinSomethingReferences_stays_andTheNameConflictStaysACase() {
    Material raw = material("Aslarite (Raw)", MaterialSourceSystem.BOTH);
    raw.setP4kUuid(P4kTestCatalogues.ASLARITE);
    Material manual = adminTwin("Aslarite");
    alias(MaterialExternalAliasSource.SCWIKI, "Aslarite", raw);
    material("Aslarite Ore", MaterialSourceSystem.UEX_ONLY).setRefinedMaterial(manual);

    apply();

    assertThat(materialRepository.findById(manual.getId())).isPresent();
    assertThat(raw.getName()).isEqualTo("Aslarite (Raw)");
    assertThat(openCase(P4kTestCatalogues.ASLARITE, P4kReviewReason.GUID_CONFLICT)).isTrue();
  }

  @Test
  void aWikiOnlyTwin_handsItsWikiUuidToTheMaterialAnAliasNames_andIsDeleted() {
    Material wiki = wikiTwin();
    Material uex = material("Construction Material Salvage", MaterialSourceSystem.UEX_ONLY);
    uex.setIdCommodity(90183);
    alias(MaterialExternalAliasSource.REFINERY_SCREEN, "Construction Salvage", uex);
    UUID wikiId = wiki.getId();
    Instant before = Instant.now().minusSeconds(1);

    apply();

    assertThat(materialRepository.findById(wikiId)).isEmpty();
    assertThat(uex.getScwikiUuid()).isEqualTo(SALVAGE_WIKI);
    assertThat(uex.getScwikiKey()).isEqualTo("ConstructionMaterialChunks");
    assertThat(uex.getSourceSystems()).isEqualTo(MaterialSourceSystem.BOTH);
    assertThat(uex.getName()).isEqualTo("Construction Material Salvage");
    assertThat(retiredSince(wikiId, before)).isTrue();
  }

  @Test
  void aWikiOnlyTwinOnlyAWikiAliasNames_stays() {
    Material wiki = wikiTwin();
    Material uex = material("Construction Material Salvage", MaterialSourceSystem.UEX_ONLY);
    alias(MaterialExternalAliasSource.SCWIKI, "Construction Salvage", uex);

    apply();

    assertThat(materialRepository.findById(wiki.getId())).isPresent();
    assertThat(uex.getScwikiUuid()).isNull();
  }

  private List<P4kImportChange> apply() {
    return P4kImportRuns.report(
        changeRepository, runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId());
  }

  private boolean openCase(UUID guid, P4kReviewReason reason) {
    return reviewCaseRepository
        .findByKindAndGuidAndReason(P4kKind.COMMODITY, guid, reason)
        .filter(review -> review.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  private boolean retiredSince(UUID materialId, Instant since) {
    return auditRecorder.recordedSince(
        AuditEventType.CATALOGUE_MATERIAL_LEFTOVER_RETIRED, materialId, since);
  }

  private Material material(String name, MaterialSourceSystem source) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.NO_REFINE);
    material.setSourceSystems(source);
    return materialRepository.save(material);
  }

  private Material seededBorase() {
    Material seeded = material("Borase", MaterialSourceSystem.P4K);
    seeded.setIsVisible(false);
    seeded.setP4kUuid(BORASE);
    seeded.setScwikiUuid(BORASE);
    return seeded;
  }

  private Material boraseTwin() {
    Material twin = material("Borase Wiki Twin", MaterialSourceSystem.BOTH);
    twin.setIdCommodity(90501);
    twin.setScwikiUuid(BORASE_TYPE);
    twin.setPriceSell(12.5);
    return twin;
  }

  private Material adminTwin(String name) {
    Material manual = material(name, MaterialSourceSystem.MANUAL);
    manual.setIsVisible(false);
    return manual;
  }

  private Material wikiTwin() {
    Material wiki = material("Construction Salvage", MaterialSourceSystem.WIKI_ONLY);
    wiki.setIsVisible(false);
    wiki.setScwikiUuid(SALVAGE_WIKI);
    wiki.setScwikiKey("ConstructionMaterialChunks");
    return wiki;
  }

  private void alias(MaterialExternalAliasSource source, String externalName, Material material) {
    MaterialExternalAlias alias = new MaterialExternalAlias();
    alias.setSourceSystem(source);
    alias.setExternalName(externalName);
    alias.setMaterial(material);
    alias.setCreatedBy("system");
    aliasRepository.save(alias);
  }

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("leftover-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user).getId();
  }
}
