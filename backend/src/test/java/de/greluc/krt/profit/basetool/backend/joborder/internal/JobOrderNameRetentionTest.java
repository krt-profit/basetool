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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The job-order area of the name retention against Postgres (REQ-DATA-047). */
@SpringBootTest
@Transactional
class JobOrderNameRetentionTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Autowired private JobOrderNameRetention retention;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void aClosedOrderLastChangedBeforeTheCutoffLosesItsNames() {
    UUID old = closedOrder("Old Requester", CUTOFF.minus(1, ChronoUnit.DAYS));
    UUID oldHandover = materialHandover(old, "Old Recipient", "Old Giver");
    UUID oldHandoverWithoutGiver = materialHandover(old, "Second Recipient", null);
    UUID oldItemHandover = itemHandover(old, "Old Item Recipient", "Old Item Giver");
    UUID oldItemHandoverWithoutGiver = itemHandover(old, "Second Item Recipient", null);
    UUID oldHolder = accountlessHolder(old, "Old Holder");
    UUID recent = closedOrder("Recent Requester", CUTOFF.plus(1, ChronoUnit.DAYS));
    UUID recentHandover = materialHandover(recent, "Recent Recipient", "Recent Giver");
    UUID recentItemHandover = itemHandover(recent, "Recent Item Recipient", "Recent Item Giver");

    assertThat(retention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(6);

    assertThat(text("job_order", "handle", old)).isEqualTo(SENTINEL);
    assertThat(text("job_order_handover", "recipient_handle", oldHandover)).isEqualTo(SENTINEL);
    assertThat(text("job_order_handover", "handing_over_handle", oldHandover)).isEqualTo(SENTINEL);
    assertThat(text("job_order_handover", "recipient_handle", oldHandoverWithoutGiver))
        .isEqualTo(SENTINEL);
    assertThat(text("job_order_handover", "handing_over_handle", oldHandoverWithoutGiver)).isNull();
    assertThat(text("job_order_item_handover", "recipient_handle", oldItemHandover))
        .isEqualTo(SENTINEL);
    assertThat(text("job_order_item_handover", "handing_over_handle", oldItemHandover))
        .isEqualTo(SENTINEL);
    assertThat(text("job_order_item_handover", "handing_over_handle", oldItemHandoverWithoutGiver))
        .isNull();
    assertThat(text("job_order_money_holder", "accountless_name", oldHolder)).isEqualTo(SENTINEL);
    assertThat(text("job_order", "handle", recent)).isEqualTo("Recent Requester");
    assertThat(text("job_order_handover", "recipient_handle", recentHandover))
        .isEqualTo("Recent Recipient");
    assertThat(text("job_order_handover", "handing_over_handle", recentHandover))
        .isEqualTo("Recent Giver");
    assertThat(text("job_order_item_handover", "handing_over_handle", recentItemHandover))
        .isEqualTo("Recent Item Giver");
    assertThat(retention.removeNamesBefore(CUTOFF)).isZero();
  }

  /** An old order whose only remaining names are handing-over handles is still cleared. */
  @Test
  void anOrderWhoseOnlyNamesAreHandingOverHandlesIsCleared() {
    UUID old = closedOrder(SENTINEL, CUTOFF.minus(1, ChronoUnit.DAYS));
    UUID materialRow = materialHandover(old, SENTINEL, "Lone Giver");
    UUID itemRow = itemHandover(old, SENTINEL, "Lone Item Giver");

    assertThat(retention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(2);

    assertThat(text("job_order_handover", "handing_over_handle", materialRow)).isEqualTo(SENTINEL);
    assertThat(text("job_order_item_handover", "handing_over_handle", itemRow)).isEqualTo(SENTINEL);
  }

  @Test
  void anOpenOrderKeepsItsNames() {
    UUID open = order("Open Requester");
    jdbc.update(
        "UPDATE job_order SET updated_at = ? WHERE id = ?",
        Timestamp.from(CUTOFF.minus(30, ChronoUnit.DAYS)),
        open);

    retention.removeNamesBefore(CUTOFF);

    assertThat(text("job_order", "handle", open)).isEqualTo("Open Requester");
  }

  private UUID closedOrder(String handle, Instant lastChange) {
    UUID id = order(handle);
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', updated_at = ?, closed_at = ? WHERE id = ?",
        Timestamp.from(lastChange),
        Timestamp.from(lastChange),
        id);
    return id;
  }

  private UUID materialHandover(UUID order, String recipient, @Nullable String handingOverHandle) {
    return handover("job_order_handover", order, recipient, handingOverHandle);
  }

  private UUID itemHandover(UUID order, String recipient, @Nullable String handingOverHandle) {
    return handover("job_order_item_handover", order, recipient, handingOverHandle);
  }

  private UUID handover(
      String table, UUID order, String recipient, @Nullable String handingOverHandle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + table
            + " (id, job_order_id, handover_time, recipient_handle, handing_over_handle,"
            + " created_at, updated_at, version) VALUES (?, ?, now(), ?, ?, now(), now(), 0)",
        id,
        order,
        recipient,
        handingOverHandle);
    return id;
  }

  private UUID accountlessHolder(UUID order, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_money_holder (id, order_id, accountless_name, revision)"
            + " VALUES (?, ?, ?, 1)",
        id,
        order,
        name);
    return id;
  }

  private String text(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
  }

  private UUID order(String handle) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    SpecialCommand sk = new SpecialCommand();
    sk.setName("Retention-SK-" + unique);
    sk.setShorthand("R" + unique);
    withinProfit(sk, persistedProfitArea(orgUnitRepository));
    specialCommandRepository.save(sk);
    Material material = new Material();
    material.setName("Retention Ore " + unique);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialRepository.save(material);
    QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(sk)
            .requestingOrgUnit(squadron)
            .handle(handle)
            .status(JobOrderStatus.OPEN)
            .build();
    order.addMaterial(
        JobOrderMaterial.builder().material(material).qualityTier(good).amount(10.0).build());
    return jobOrderRepository.saveAndFlush(order).getId();
  }
}
