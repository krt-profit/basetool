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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Verifies against the database that a handover credits its line and that an order edit keeps the
 * line and its credit (REQ-ORDERS-077, ADR-0400).
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobOrderMaterialEditCreditIntegrationTest extends MaterialContributionTestSupport {

  private static final int GOOD_FLOOR = 650;

  /** A handover credits the line and persists the credit, and edits keep the line and credit. */
  @Test
  void aHandoverCreditsItsLineAndAnEditKeepsIt() throws Exception {
    Fixture fixture = seed();
    StockFixture stock = stock(fixture, 12.0);
    earmark(fixture, stock, 12.0);
    UUID lineId = lineId(fixture.orderId());

    handOver(fixture, stock, 4.0);

    assertThat(lineFacts(lineId)).containsEntry("amount", 10.0).containsEntry("credited", 4.0);
    assertThat(
            jdbc.queryForList(
                "SELECT c.amount, i.surplus_amount FROM job_order_handover_credit c"
                    + " JOIN job_order_handover_item i ON i.id = c.job_order_handover_item_id"
                    + " WHERE c.job_order_material_id = ?",
                lineId))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("amount")).isEqualTo(4.0);
          assertThat(row.get("surplus_amount")).isEqualTo(0.0);
        });
    JobOrderMaterialDto line = onlyLine(jobOrderQueryService.getJobOrderById(fixture.orderId()));
    assertThat(line.amount()).isEqualTo(10.0);
    assertThat(line.handedOverAmount()).isEqualTo(4.0);
    assertThat(line.remainingAmount()).isEqualTo(6.0);

    String body =
        update(fixture, 12.0)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JobOrderDto edited = json.readValue(body, JobOrderDto.class);

    assertThat(onlyLine(edited).id()).as("the edit keeps the line's identity").isEqualTo(lineId);
    assertThat(onlyLine(edited).remainingAmount()).isEqualTo(8.0);
    assertThat(lineFacts(lineId)).containsEntry("amount", 12.0).containsEntry("credited", 4.0);

    update(fixture, 3.0).andExpect(status().isBadRequest());
    assertThat(lineFacts(lineId)).containsEntry("amount", 12.0).containsEntry("credited", 4.0);
  }

  /** An over-delivery fulfils the line, records the surplus and completes the order. */
  @Test
  void anOverDeliveryIsRecordedAsSurplus() throws Exception {
    Fixture fixture = seed();
    StockFixture stock = stock(fixture, 12.0);
    earmark(fixture, stock, 12.0);
    UUID lineId = lineId(fixture.orderId());

    handOver(fixture, stock, 12.0);

    assertThat(lineFacts(lineId)).containsEntry("amount", 10.0).containsEntry("credited", 10.0);
    assertThat(
            jdbc.queryForObject(
                "SELECT i.surplus_amount FROM job_order_handover_item i"
                    + " JOIN job_order_handover h ON h.id = i.job_order_handover_id"
                    + " WHERE h.job_order_id = ?",
                Double.class,
                fixture.orderId()))
        .isEqualTo(2.0);
    assertThat(jobOrderQueryService.getJobOrderById(fixture.orderId()).status())
        .isEqualTo(JobOrderStatus.COMPLETED);
  }

  private void earmark(@NotNull Fixture fixture, @NotNull StockFixture stock, double amount)
      throws Exception {
    mvc()
        .perform(
            post("/api/v1/inventory/{id}/allocation", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "field",
                            "JOB_ORDER",
                            "targetId",
                            fixture.orderId(),
                            "amount",
                            amount,
                            "version",
                            stockReads.findRow(stock.rowId()).orElseThrow().getVersion()))))
        .andExpect(status().isOk());
  }

  private void handOver(@NotNull Fixture fixture, @NotNull StockFixture stock, double amount)
      throws Exception {
    var request =
        new JobOrderHandoverCreateDto(
            Instant.now(),
            handingOverMember(fixture),
            "Handover recipient",
            null,
            List.of(new JobOrderHandoverItemCreateDto(stock.rowId(), amount, null, null)));
    mvc()
        .perform(
            post("/api/v1/orders/{order}/handovers", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201));
  }

  private @NotNull ResultActions update(@NotNull Fixture fixture, double requested)
      throws Exception {
    JobOrderDto current = jobOrderQueryService.getJobOrderById(fixture.orderId());
    CreateJobOrderDto edit =
        new CreateJobOrderDto(
            null,
            null,
            current.handle(),
            current.comment(),
            List.of(new CreateJobOrderMaterialDto(fixture.materialId(), GOOD_FLOOR, requested)),
            new JobOrderObservedVersions(current.version(), current.commentVersion()));
    return mvc()
        .perform(
            put("/api/v1/orders/{id}", fixture.orderId())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit)));
  }

  private @NotNull UUID lineId(@NotNull UUID orderId) {
    return jdbc.queryForObject(
        "SELECT id FROM job_order_material WHERE job_order_id = ?", UUID.class, orderId);
  }

  private @NotNull Map<String, Object> lineFacts(@NotNull UUID lineId) {
    return jdbc.queryForMap(
        "SELECT amount, credited_amount AS credited FROM job_order_material WHERE id = ?", lineId);
  }

  private static @NotNull JobOrderMaterialDto onlyLine(@NotNull JobOrderDto order) {
    assertThat(order.materials()).hasSize(1);
    return order.materials().getFirst();
  }
}
