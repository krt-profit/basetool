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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.AuecProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.Versions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The member-independent exchange read of a job order, projected from its current rows
 * (REQ-OXCH-003): the header, the versions, active support and assignees by id, and each kind's
 * progress as the web shows it.
 */
@SpringBootTest
class JobOrderExchangeReadProjectionIntegrationTest extends MaterialContributionTestSupport {

  @Autowired private JobOrderExchangeReads reads;
  @Autowired private JobOrderMoneyTargetRepository moneyTargets;
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;

  @Test
  void aMaterialOrderCarriesItsHeaderVersionsAndOneLinePerMaterialAndTier() {
    Fixture fixture = seed();
    jdbc.update(
        "UPDATE job_order SET comment = 'Bitte bis Freitag', priority = 3 WHERE id = ?",
        fixture.orderId());

    JobOrderExchangeRead read = readOf(fixture.orderId());

    assertThat(read.id()).isEqualTo(fixture.orderId());
    assertThat(read.number()).isEqualTo(displayIdOf(fixture.orderId()));
    assertThat(read.kind()).isEqualTo(JobOrderType.MATERIAL);
    assertThat(read.status()).isEqualTo(JobOrderStatus.OPEN);
    assertThat(read.priority()).isEqualTo(3);
    assertThat(read.requestingUnitId()).isEqualTo(fixture.squadronAId());
    assertThat(read.responsibleUnitId()).isEqualTo(responsibleOf(fixture.orderId()));
    assertThat(read.comment()).isEqualTo("Bitte bis Freitag");
    assertThat(read.contact()).isEqualTo("claim-test");
    assertThat(read.responsibilityInvalid()).isFalse();
    assertThat(read.versions()).isEqualTo(new Versions(0, null, 0));
    assertThat(read.supportingUnitIds()).isEmpty();
    assertThat(read.assigneeIds()).isEmpty();
    assertThat(read.progress())
        .isEqualTo(
            new MaterialProgress(
                List.of(new MaterialLine(fixture.materialId(), goodTier(), 10, 0, 10, 10))));
  }

  @Test
  void aHandoverAClaimAndEarmarkedStockLowerWhatIsOpen() throws Exception {
    Fixture fixture = seed();
    jdbc.update(
        "UPDATE job_order_material SET credited_amount = 2 WHERE job_order_id = ?",
        fixture.orderId());
    claim(fixture, fixture.squadronAId(), 3.0);
    earmark(fixture, stock(fixture, 1.0), 1.0);

    JobOrderExchangeRead read = readOf(fixture.orderId());

    assertThat(read.progress())
        .isEqualTo(
            new MaterialProgress(
                List.of(new MaterialLine(fixture.materialId(), goodTier(), 10, 2, 8, 4))));
    assertThat(read.supportingUnitIds()).containsExactly(fixture.squadronAId());
  }

  @Test
  void endedSupportIsLeftOutAndAssigneesAreCarriedById() throws Exception {
    Fixture fixture = seed();
    claim(fixture, fixture.squadronAId(), 1.0);
    claim(fixture, fixture.squadronBId(), 1.0);
    jdbc.update(
        "UPDATE job_order_participation SET active = FALSE"
            + " WHERE job_order_id = ? AND claiming_org_unit_id = ?",
        fixture.orderId(),
        fixture.squadronBId());
    UUID assignee = stock(fixture, 0.001).holderId();
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        fixture.orderId(),
        assignee,
        "Nur meine Notiz");

    JobOrderExchangeRead read = readOf(fixture.orderId());

    assertThat(read.supportingUnitIds()).containsExactly(fixture.squadronAId());
    assertThat(read.assigneeIds()).containsExactly(assignee);
  }

  @Test
  void anItemOrderCarriesItsUnitsPerLine() {
    Fixture fixture = seed();
    ItemLine expected =
        Objects.requireNonNull(
            transactionTemplate.execute(_ -> {
              JobOrder order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
              order.setType(JobOrderType.ITEM);
              order.getMaterials().clear();
              GameItem item = new GameItem();
              item.setName("Projection output " + UUID.randomUUID());
              item = gameItems.saveAndFlush(item);
              Blueprint blueprint = new Blueprint();
              blueprint.setOutputItem(item);
              blueprint.setOutputName(item.getName());
              blueprint = blueprints.saveAndFlush(blueprint);
              JobOrderItem line =
                  JobOrderItem.builder()
                      .jobOrder(order)
                      .gameItem(item)
                      .blueprint(blueprint)
                      .amount(5)
                      .manufacturedAmount(3)
                      .deliveredAmount(2)
                      .build();
              order.getItems().add(line);
              UUID lineId =
                  jobOrderRepository.saveAndFlush(order).getItems().iterator().next().getId();
              return new ItemLine(lineId, item.getId(), 5, 3, 2, 3);
            }));

    JobOrderExchangeRead read = readOf(fixture.orderId());

    assertThat(read.kind()).isEqualTo(JobOrderType.ITEM);
    assertThat(read.contact()).isEqualTo("claim-test");
    assertThat(read.progress()).isEqualTo(new ItemProgress(List.of(expected)));
  }

  @Test
  void anAuecOrderCarriesNoContactButItsTargetVersionAndFigures() {
    Fixture fixture = seed();
    toAuec(fixture);

    JobOrderExchangeRead read = readOf(fixture.orderId());

    assertThat(read.contact()).isNull();
    assertThat(read.versions()).isEqualTo(new Versions(0, 0L, 0));
    assertThat(read.progress())
        .isEqualTo(
            new AuecProgress(
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                false));
  }

  @Test
  void aContactChangeOnAnAuecOrderLeavesTheReadUnchanged() {
    Fixture fixture = seed();
    toAuec(fixture);

    JobOrderExchangeRead before = readOf(fixture.orderId());

    jdbc.update(
        "UPDATE job_order SET handle = 'Other contact',"
            + " money_information_version = money_information_version + 1 WHERE id = ?",
        fixture.orderId());

    assertThat(readOf(fixture.orderId())).isEqualTo(before);
  }

  @Test
  void aClaimThatLeavesOpenUnchangedLeavesTheReadUnchanged() throws Exception {
    Fixture fixture = seed();
    ClaimDto promise = claim(fixture, fixture.squadronAId(), 3.0);
    StockFixture stock = stock(fixture, 2.0);
    JobOrderExchangeRead before = readOf(fixture.orderId());

    provide(fixture, promise, stock, 2.0);

    assertThat(before.progress())
        .isEqualTo(
            new MaterialProgress(
                List.of(new MaterialLine(fixture.materialId(), goodTier(), 10, 0, 10, 7))));
    assertThat(readOf(fixture.orderId())).isEqualTo(before);
  }

  @Test
  void aResponsibleUnitThatLeftTheProfitHierarchyMakesTheResponsibilityInvalid() {
    Fixture fixture = seed();
    jdbc.update(
        "UPDATE org_unit SET active = FALSE WHERE id = ?", responsibleOf(fixture.orderId()));

    assertThat(readOf(fixture.orderId()).responsibilityInvalid()).isTrue();
  }

  @Test
  void anIdWithoutAnOrderIsAbsent() {
    Map<UUID, JobOrderExchangeRead> projected =
        transactionTemplate.execute(_ -> reads.project(Set.of(UUID.randomUUID())));

    assertThat(projected).isEmpty();
  }

  /**
   * Turns the fixture into an aUEC order with a target of 1000.
   *
   * @param fixture the order
   */
  private void toAuec(Fixture fixture) {
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      order.setType(JobOrderType.AUEC);
      order.getMaterials().clear();
      jobOrderRepository.saveAndFlush(order);
      moneyTargets.saveAndFlush(new JobOrderMoneyTarget(order.getId(), new BigDecimal("1000")));
    });
  }

  /**
   * Projects one order in its own transaction.
   *
   * @param orderId the order
   * @return its read
   */
  private JobOrderExchangeRead readOf(UUID orderId) {
    Map<UUID, JobOrderExchangeRead> projected =
        Objects.requireNonNull(transactionTemplate.execute(_ -> reads.project(Set.of(orderId))));
    return Objects.requireNonNull(projected.get(orderId));
  }

  /**
   * Creates a claim of a Staffel on the order's single bucket through the public API.
   *
   * @param fixture the order
   * @param squadron the claiming Staffel
   * @param amount the claimed amount
   * @throws Exception when the request fails
   */
  private ClaimDto claim(Fixture fixture, UUID squadron, double amount) throws Exception {
    return claim(fixture, claim(fixture.materialId(), squadron, amount));
  }

  /**
   * Writes a claim through the public API.
   *
   * @param fixture the order
   * @param terms the claim, with the observed version of an existing one
   * @return the written claim
   * @throws Exception when the request fails
   */
  private ClaimDto claim(Fixture fixture, CreateClaimDto terms) throws Exception {
    UUID claimant = stock(fixture, 0.001).holderId();
    String body =
        mvc()
            .perform(
                post("/api/v1/orders/{id}/claims", fixture.orderId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(claimant.toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(terms)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, ClaimDto.class);
  }

  /**
   * Earmarks part of a stock row for the order, as a bulk path would.
   *
   * @param fixture the order
   * @param stock the stock row
   * @param amount the earmarked amount
   */
  private void earmark(Fixture fixture, StockFixture stock, double amount) {
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        stock.rowId(),
        fixture.orderId(),
        amount);
  }

  private int displayIdOf(UUID orderId) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT display_id FROM job_order WHERE id = ?", Integer.class, orderId));
  }

  private UUID responsibleOf(UUID orderId) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT responsible_org_unit_id FROM job_order WHERE id = ?", UUID.class, orderId));
  }

  private UUID goodTier() {
    return Objects.requireNonNull(qualityTierRepository.findByCode("GOOD").orElseThrow().getId());
  }
}
