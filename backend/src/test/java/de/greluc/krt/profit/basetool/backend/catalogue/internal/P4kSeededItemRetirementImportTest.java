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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** A seeded item no rule would create and no member references hides itself (REQ-P4K-056). */
@SpringBootTest
@Transactional
class P4kSeededItemRetirementImportTest {

  /** The fixture's seat access: named, {@code playability} {@code null}. */
  private static final UUID SEAT = UUID.fromString("000ec670-7acc-4771-b567-7c939d2a80c0");

  /** The fixture's heavy-armour blueprint. */
  private static final UUID HEAVY_BLUEPRINT =
      UUID.fromString("005d95db-96ca-45b7-9647-7e7537b8fac8");

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private GameItemRepository items;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(imports, jobs);
  }

  @Test
  void anUnreferencedSeededItemNoRuleWouldCreateIsHidden() {
    GameItem seeded = items.saveAndFlush(seededSeat());

    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    assertThat(seeded.getP4kHiddenAt()).isNotNull();
    assertThat(seededCase()).isFalse();
    assertThat(P4kImportRuns.report(changes, runId))
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.CHANGED
                && change.getKind() == P4kKind.ITEM
                && SEAT.equals(change.getGuid())
                && "p4k_hidden_at".equals(change.getFieldName()));
  }

  @Test
  void aSeededItemADefaultBlueprintProducesStaysACase() {
    GameItem seeded = items.saveAndFlush(seededSeat());
    entityManager
        .createNativeQuery(
            "INSERT INTO default_blueprint (id, product_key, product_name, output_item_id)"
                + " VALUES (:id, 'seat', 'Seat', :item)")
        .setParameter("id", UUID.randomUUID())
        .setParameter("item", seeded.getId())
        .executeUpdate();

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(seeded.getP4kHiddenAt()).isNull();
    assertThat(seededCase()).isTrue();
  }

  @Test
  void aSeededItemACatalogueBlueprintProducesStaysACase() {
    GameItem seeded = items.saveAndFlush(seededSeat());
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "blueprints", HEAVY_BLUEPRINT)
        .put("producedItemGuid", SEAT.toString());

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(seeded.getP4kHiddenAt()).isNull();
    assertThat(seededCase()).isTrue();
  }

  private boolean seededCase() {
    return cases
        .findByKindAndGuidAndReason(P4kKind.ITEM, SEAT, P4kReviewReason.SEEDED_NOT_CREATABLE)
        .filter(review -> review.getStatus() == P4kReviewStatus.OPEN)
        .isPresent();
  }

  private static GameItem seededSeat() {
    GameItem item = new GameItem();
    item.setName("Seat");
    item.setClassName("ORIG_600i_Pilot_SeatAccess");
    item.setKind(GameItemKind.GENERIC);
    item.setP4kUuid(SEAT);
    item.setSourceSystems(GameItemSourceSystem.P4K);
    return item;
  }
}
