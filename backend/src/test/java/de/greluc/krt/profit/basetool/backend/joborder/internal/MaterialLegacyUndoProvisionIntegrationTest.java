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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies legacy Undo credit ineligibility and genuine new provision through public actions. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialLegacyUndoProvisionIntegrationTest extends MaterialContributionTestSupport {
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String INSTALLATION = "Ku9_" + "u".repeat(39);
  @Autowired private ExchangeClientRepository clients;
  @Autowired private ExchangeSettingsRepository settings;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private RoleRepository roles;

  @Test
  void restoredUnknownFiveCannotBeCreditedButGenuineNewOneCan() throws Exception {
    Fixture fixture = seed();
    UUID pool =
        transactionTemplate.execute(_ -> {
          var order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
          order.getMaterials().iterator().next().setAmount(20.0);
          jobOrderRepository.saveAndFlush(order);
          return order.getResponsibleOrgUnit().getId();
        });
    var claim =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 6.0));
    StockFixture source = stock(fixture, 5.0);
    transactionTemplate.executeWithoutResult(_ -> {
      var actor = users.findById(source.holderId()).orElseThrow();
      var granted = new HashSet<>(actor.getRoles());
      granted.add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
      actor.setRoles(granted);
      users.saveAndFlush(actor);
    });
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', TRUE)",
        source.holderId(),
        pool);
    ExchangeClient client = new ExchangeClient();
    client.setClientId("legacy-credit-" + UUID.randomUUID().toString().substring(0, 8));
    client.setDisplayName("Legacy provision fixture");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.ORGSTOCK_READ,
            ExchangeCapability.ORGSTOCK_DETAILS,
            ExchangeCapability.ORGSTOCK_WRITE));
    client = clients.saveAndFlush(client);
    knownClients.invalidate();
    var priorSettings = settings.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    boolean previouslyEnabled = priorSettings.isEnabled();
    priorSettings.setEnabled(true);
    settings.saveAndFlush(priorSettings);
    try {
      allocate(fixture, source.rowId(), source.holderId(), pool, 5.0, true, null);
      var initial = contributionHistory(fixture.orderId(), claim.id());
      assertThat(initial.openAmount()).isEqualTo(6.0);
      assertThat(initial.creditedAmount()).isZero();
      var batch = json.createObjectNode();
      var operation =
          batch
              .putArray("ops")
              .addObject()
              .put("op", "book-out")
              .put("unit", pool.toString())
              .put("entry", source.rowId().toString())
              .put("version", stockReads.findRow(source.rowId()).orElseThrow().getVersion());
      operation.putObject("quantity").put("amount", 5).put("unit", "SCU");
      operation
          .putArray("fromJobOrders")
          .addObject()
          .put("jobOrder", fixture.orderId().toString())
          .putObject("quantity")
          .put("amount", 5)
          .put("unit", "SCU");
      mvc()
          .perform(
              post("/api/v1/exchange/org-stock/changes")
                  .with(
                      jwt()
                          .jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                  .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, source.holderId().toString())
                  .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client.getClientId())
                  .header(
                      ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.orgstock.write")
                  .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, INSTALLATION)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batch.toString()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.applied").value(1));
      assertThat(stockReads.findRow(source.rowId())).isEmpty();
      assertThat(
              jdbc.update(
                  "UPDATE exchange_journal SET before_state = CAST((CAST(before_state AS jsonb)"
                      + " - 'quantityProvenance' - 'completeQuantity') AS text)"
                      + " WHERE user_id = ? AND client_id = ? AND entity_key = ?",
                  source.holderId(),
                  client.getClientId(),
                  source.rowId().toString()))
          .isEqualTo(1);
      mvc()
          .perform(
              post("/api/v1/connected-apps/{client}/undo", client.getClientId())
                  .with(asWriter(source.holderId(), pool))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.createObjectNode()
                          .put("since", Instant.now().minus(1, ChronoUnit.HOURS).toString())
                          .toString()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.restored").value(1))
          .andExpect(jsonPath("$.skipped.length()").value(0));
      assertStock(fixture, source.holderId(), pool, 5.0);
      assertThat(contributionHistory(fixture.orderId(), claim.id())).isEqualTo(initial);

      mvc()
          .perform(
              delete("/api/v1/inventory/{id}/allocation", source.rowId())
                  .with(asWriter(source.holderId(), pool))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.createObjectNode()
                          .put("field", "JOB_ORDER")
                          .put("targetId", fixture.orderId().toString())
                          .put(
                              "version",
                              stockReads.findRow(source.rowId()).orElseThrow().getVersion())
                          .toString()))
          .andExpect(status().isOk());
      allocate(
          fixture,
          source.rowId(),
          source.holderId(),
          pool,
          5.0,
          true,
          contributionHistory(fixture.orderId(), claim.id()));
      var uncredited = contributionHistory(fixture.orderId(), claim.id());
      assertThat(uncredited.creditedAmount()).isZero();
      assertThat(uncredited.openAmount()).isEqualTo(6.0);
      assertThat(uncredited).isEqualTo(initial);
      var retained = assertStock(fixture, source.holderId(), pool, 5.0);

      String created =
          mvc()
              .perform(
                  post("/api/v1/inventory")
                      .with(asWriter(source.holderId(), pool))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.createObjectNode()
                              .put("materialId", fixture.materialId().toString())
                              .put("locationId", retained.locationId().toString())
                              .put("quality", retained.quality().intValue())
                              .put("amount", 1.0)
                              .put("personal", false)
                              .put("stolen", false)
                              .put("owningOrgUnitId", pool.toString())
                              .put("mergeStock", true)
                              .toString()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      UUID fresh = UUID.fromString(json.readTree(created).path("id").asString());
      allocate(fixture, fresh, source.holderId(), pool, 6.0, false, uncredited);
      var after = contributionHistory(fixture.orderId(), claim.id());
      assertThat(after.creditedAmount()).isEqualTo(1.0);
      assertThat(after.openAmount()).isEqualTo(5.0);
      assertThat(after.provisions())
          .singleElement()
          .satisfies(receipt -> {
            assertThat(receipt.amount()).isEqualTo(1.0);
            assertThat(receipt.quantityAmount()).isEqualTo(1.0);
          });
      assertStock(fixture, source.holderId(), pool, 6.0);
    } finally {
      var restoredSettings = settings.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
      restoredSettings.setEnabled(previouslyEnabled);
      settings.saveAndFlush(restoredSettings);
      jdbc.update("DELETE FROM exchange_client WHERE id = ?", client.getId());
      knownClients.invalidate();
    }
  }

  /**
   * Applies an ordinary versioned allocation with an optional deliberately selected contribution.
   */
  private void allocate(
      Fixture fixture,
      UUID row,
      UUID actor,
      UUID pool,
      double amount,
      boolean create,
      @Nullable MaterialContributionHistoryDto provision)
      throws Exception {
    var payload =
        json.createObjectNode()
            .put("field", "JOB_ORDER")
            .put("targetId", fixture.orderId().toString())
            .put("amount", amount)
            .put("version", stockReads.findRow(row).orElseThrow().getVersion());
    if (provision != null) {
      payload
          .putObject("provision")
          .put("claimId", provision.id().toString())
          .put("claimVersion", provision.version());
    }
    MockHttpServletRequestBuilder request =
        create
            ? post("/api/v1/inventory/{id}/allocation", row)
            : patch("/api/v1/inventory/{id}/allocation", row);
    mvc()
        .perform(
            request
                .with(asWriter(actor, pool))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()))
        .andExpect(status().isOk());
  }

  /**
   * Checks physical coverage through the concrete order's public material collection.
   *
   * @return the authorized current row used to declare a later ordinary book-in
   */
  private MaterialCollectionEntryDto assertStock(
      Fixture fixture, UUID actor, UUID pool, double amount) throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/orders/{id}/material-collection", fixture.orderId())
                    .with(asWriter(actor, pool)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<MaterialCollectionEntryDto> rows =
        json.readValue(
            body, new tools.jackson.core.type.TypeReference<List<MaterialCollectionEntryDto>>() {});
    assertThat(rows)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.quantity()).isEqualTo(amount);
          assertThat(row.allocatedQuantity()).isEqualTo(amount);
        });
    return rows.getFirst();
  }

  /**
   * Uses the holder's actual responsible-pool Logistician grant without administrator authority.
   */
  private RequestPostProcessor asWriter(UUID actor, UUID pool) {
    return jwt()
        .jwt(token -> token.subject(actor.toString()).claim("azp", "basetool-frontend"))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, pool));
  }
}
