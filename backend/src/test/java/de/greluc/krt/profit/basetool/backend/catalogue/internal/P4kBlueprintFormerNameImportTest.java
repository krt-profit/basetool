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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
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
 * An output name a P4K APPLY replaces stays a name of the blueprint's product, so a member's key
 * derived from it moves to the product in the same run (REQ-P4K-079).
 */
@SpringBootTest
@Transactional
class P4kBlueprintFormerNameImportTest {

  private static final UUID LIGHT_BLUEPRINT =
      UUID.fromString("0037f308-adfc-4c4e-994f-cd477e895aab");
  private static final UUID LIGHT_ITEM = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");
  private static final String ITEM_NAME = "TrueDef-Pro Arms Silver/Gold/Grey";
  private static final String FORMER_NAME = "Old Light Arms";
  private static final String FORMER_KEY = "old light arms";

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private JdbcTemplate jdbc;

  private P4kImportRuns runs;
  private UUID member;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    member = member();
    GameItem item = new GameItem();
    item.setName(ITEM_NAME);
    item.setClassName("vgl_armor_light_arms_01_01_20");
    item.setKind(GameItemKind.ARMOR);
    item.setP4kUuid(LIGHT_ITEM);
    gameItemRepository.saveAndFlush(item);
  }

  @Test
  void aMemberKeyOfTheReplacedName_movesToTheProductInTheSameRun() {
    Blueprint renamed = blueprint(LIGHT_BLUEPRINT, FORMER_NAME);
    owned(FORMER_KEY);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(renamed.getOutputName()).isEqualTo(ITEM_NAME);
    assertThat(
            jdbc.queryForList(
                "SELECT name FROM blueprint_former_name WHERE blueprint_id = ?",
                String.class,
                renamed.getId()))
        .containsExactly(FORMER_NAME);
    assertThat(memberKeys()).containsExactly(LIGHT_ITEM.toString());
    assertThat(openCase(P4kReviewReason.PRODUCT_KEY_UNRESOLVED)).isFalse();
  }

  @Test
  void aReplacedNameAnotherProductCarries_staysAmbiguous() {
    blueprint(LIGHT_BLUEPRINT, FORMER_NAME);
    blueprint(null, FORMER_NAME);
    owned(FORMER_KEY);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(memberKeys()).containsExactly(FORMER_KEY);
    assertThat(openCase(P4kReviewReason.PRODUCT_KEY_AMBIGUOUS)).isTrue();
  }

  @Test
  void aPreviewKeepsNoReplacedName() {
    Blueprint renamed = blueprint(LIGHT_BLUEPRINT, FORMER_NAME);

    runs.preview(P4kTestCatalogues.fixtureBytes());

    assertThat(renamed.getOutputName()).isEqualTo(FORMER_NAME);
    assertThat(renamed.getFormerNames()).isEmpty();
  }

  private boolean openCase(P4kReviewReason reason) {
    return caseRepository
        .findByKindAndGuidAndReason(
            P4kKind.BLUEPRINT, P4kProductKeyReview.caseGuid(FORMER_KEY), reason)
        .map(c -> c.getStatus() == P4kReviewStatus.OPEN)
        .orElse(false);
  }

  private Blueprint blueprint(UUID p4kUuid, String outputName) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("BP_FORMER_NAME_TEST_" + UUID.randomUUID());
    blueprint.setOutputName(outputName);
    blueprint.setP4kUuid(p4kUuid);
    return blueprintRepository.saveAndFlush(blueprint);
  }

  private void owned(String productKey) {
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        productKey,
        FORMER_NAME);
  }

  private List<String> memberKeys() {
    return jdbc.queryForList(
        "SELECT product_key FROM personal_blueprint WHERE owner_user_id = ?", String.class, member);
  }

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("former-name-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user).getId();
  }
}
