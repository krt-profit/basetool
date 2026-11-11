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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Pin;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises linked settlement, local availability and actual payment recovery through HTTP. */
@SpringBootTest
@Transactional
class JobOrderLinkedBankSettlementIntegrationTest {
  private static final String MONEY = "/api/v1/orders/{id}/money";
  private static final String SETTLEMENT = MONEY + "/movements/settlement";

  @Autowired private ConfigurableWebApplicationContext context;
  @Autowired private OrgUnitRepository units;
  @Autowired private CustomJwtGrantedAuthoritiesConverter authorities;
  @Autowired private SystemSettings settings;
  @Autowired private JsonMapper json;

  private BankAccessWorld world;
  private MockMvc mvc;
  private UUID profit;

  @BeforeEach
  void seed() {
    world = new BankAccessSeeder(context).seed();
    profit = units.findActiveProfitRootIds().getFirst();
    settings.putSettingValue("operation.transfer_fee_rate", "0.005");
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void paidBeneficiarySettlementBindsPrincipalAndFeeUntilBankConfirmation() throws Exception {
    UUID order = fundedOrder("1000000");
    JsonNode source = holder(order);
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    send(post(SETTLEMENT, order).with(writer()), settlement(source, custodian))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holders[0].amount").value("600000"))
        .andExpect(jsonPath("$.holders[0].availableAmount").value("298500"))
        .andExpect(jsonPath("$.settled").value("0"));
    confirm(linkedRequest("WITHDRAWAL"), Actor.EMPLOYEE_WITHDRAW, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultingTransactionId").isNotEmpty())
        .andExpect(jsonPath("$.counterpartyHandle").value("Actual accountless beneficiary"));
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holders[0].amount").value("298500"))
        .andExpect(jsonPath("$.holders[0].availableAmount").value("298500"))
        .andExpect(jsonPath("$.settled").value("300000"))
        .andExpect(jsonPath("$.fees").value("1500"));
  }

  @Test
  void aCorrectedSourceHolderCannotBePaidThroughTheStaleBankCustodian() throws Exception {
    UUID order = fundedOrder("1000000");
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    send(post(SETTLEMENT, order).with(writer()), settlement(holder(order), custodian))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode current = holder(order);
    send(
            patch(MONEY + "/holders/{holder}/identity", order, current.path("id").asString())
                .with(writer()),
            Map.of(
                "kind", "CORRECT_LINK",
                "version", current.path("version").asLong(),
                "userId", world.user(Actor.MANAGEMENT)))
        .andExpect(status().isNoContent());

    confirm(linkedRequest("WITHDRAWAL"), Actor.EMPLOYEE_WITHDRAW, custodian)
        .andExpect(status().isConflict());
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("600000"))
        .andExpect(jsonPath("$.holders[0].availableAmount").value("298500"))
        .andExpect(jsonPath("$.settled").value("0"));
  }

  @Test
  void pendingRequesterReturnBindsDeliveredPrincipalAndReopensOnlyAfterConfirmation()
      throws Exception {
    UUID order = fundedOrder("300000");
    JsonNode source = holder(order);
    String recipient =
        body(send(
                    post(MONEY + "/recipients", order).with(writer()),
                    Map.of("userId", world.user(Actor.MANAGEMENT)))
                .andExpect(status().isCreated()))
            .path("id")
            .asString();
    send(
            post(MONEY + "/movements", order).with(writer()),
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "kind",
                "HANDOVER",
                "sourceHolderId",
                source.path("id").asString(),
                "sourceVersion",
                source.path("version").asLong(),
                "recipientId",
                recipient,
                "targetVersion",
                0,
                "amount",
                "300000",
                "rate",
                "0.005",
                "bankHandling",
                false))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    JsonNode updated = holder(order);
    var terms =
        new HashMap<String, Object>(
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "kind",
                "REQUESTER_RETURN",
                "recipientId",
                recipient,
                "destinationHolderId",
                updated.path("id").asString(),
                "destinationVersion",
                updated.path("version").asLong(),
                "targetVersion",
                0,
                "amount",
                "150000",
                "rate",
                "0.005",
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type",
                    "DEPOSIT",
                    "accountId",
                    world.account(Account.OWN_ORG_UNIT),
                    "custodianId",
                    custodian)));
    send(post(SETTLEMENT, order).with(writer()), terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.netHandedOver").value("300000"))
        .andExpect(jsonPath("$.holdings").value("298500"));

    var competing = new HashMap<>(terms);
    competing.remove("bank");
    competing.put("bankHandling", false);
    competing.put("operationId", UUID.randomUUID());
    competing.put("amount", "200000");
    competing.put("provision", "1000");
    send(post(SETTLEMENT, order).with(writer()), competing).andExpect(status().isConflict());

    confirm(linkedRequest("DEPOSIT"), Actor.ADMIN, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counterpartyUserId").value(world.user(Actor.MANAGEMENT).toString()));
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.netHandedOver").value("150000"))
        .andExpect(jsonPath("$.holdings").value("448500"))
        .andExpect(jsonPath("$.fees").value("1500"));
    mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualFirstWithdrawalCreditsTheNetProvisionAndFundsItsFeeFromTheBank(boolean coversFee)
      throws Exception {
    UUID order = createOrder("8000000");
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID receivingPlayer = world.user(Actor.MANAGEMENT);
    depositBank(account, custodian, coversFee ? "3020000" : "3000000");
    send(
            put("/api/v1/orders/{id}/participations/{unitId}/money-commitment", order, profit)
                .with(writer()),
            Map.of("amount", "4000000"))
        .andExpect(status().isNoContent());
    String commitment =
        body(participations(order).andExpect(status().isOk()))
            .path("moneyCommitments")
            .get(0)
            .path("id")
            .asString();
    var terms =
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "amount",
            "4000000",
            "bankHandling",
            true,
            "holderVersion",
            0,
            "holderUserId",
            receivingPlayer,
            "commitmentId",
            commitment,
            "bank",
            Map.of(
                "type",
                "WITHDRAWAL",
                "accountId",
                account,
                "custodianId",
                custodian,
                "paymentReported",
                true,
                "justification",
                "Provide game funds to the order holder"));
    send(post(MONEY + "/provisions", order).with(writer()), terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = linkedRequest("WITHDRAWAL");
    if (!coversFee) {
      confirm(request, Actor.ADMIN, custodian)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("BANK_OVERDRAFT"));
      mvc.perform(get(MONEY, order).with(writer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.provided").value("0"))
          .andExpect(jsonPath("$.holdings").value("0"));
      participations(order)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("4000000"));
      return;
    }
    confirm(request, Actor.ADMIN, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counterpartyUserId").value(receivingPlayer.toString()))
        .andExpect(jsonPath("$.resultingTransactionId").isNotEmpty());
    mvc.perform(
            get("/api/v1/bank/accounts/{id}", account)
                .with(jwt().jwt(token(Actor.ADMIN)).authorities(authorities::convert)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account.balance").value(0));
    mvc.perform(get(MONEY, order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("4000000"))
        .andExpect(jsonPath("$.holdings").value("4000000"))
        .andExpect(jsonPath("$.holders[0].availableAmount").value("4000000"))
        .andExpect(jsonPath("$.fees").value("0"))
        .andExpect(jsonPath("$.linkedAttempts[0].actualFee").value("20000"))
        .andExpect(jsonPath("$.linkedAttempts[0].feeFunding").value("BANK_SOURCE"));
    participations(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("0"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[1].reason").value("FULFILLED"));
  }

  private ResultActions participations(UUID order) throws Exception {
    return mvc.perform(get("/api/v1/orders/{id}/participations", order).with(writer()));
  }

  private void depositBank(UUID account, UUID custodian, String amount) throws Exception {
    send(
            post("/api/v1/bank/deposits")
                .with(jwt().jwt(token(Actor.ADMIN)).authorities(authorities::convert)),
            Map.of("accountId", account, "holderId", custodian, "amount", amount))
        .andExpect(status().isCreated());
  }

  private Map<String, ?> settlement(JsonNode source, UUID custodian) {
    return Map.of(
        "operationId",
        UUID.randomUUID(),
        "kind",
        "SETTLEMENT",
        "sourceHolderId",
        source.path("id").asString(),
        "sourceVersion",
        source.path("version").asLong(),
        "destinationName",
        "Actual accountless beneficiary",
        "amount",
        "300000",
        "rate",
        "0.005",
        "bankHandling",
        true,
        "bank",
        Map.of(
            "type",
            "WITHDRAWAL",
            "accountId",
            world.account(Account.OWN_ORG_UNIT),
            "custodianId",
            custodian,
            "paymentReported",
            true,
            "justification",
            "Pay recorded beneficiary"));
  }

  private JsonNode holder(UUID order) throws Exception {
    return body(mvc.perform(get(MONEY, order).with(writer())).andExpect(status().isOk()))
        .path("holders")
        .get(0);
  }

  private UUID createOrder(String target) throws Exception {
    JsonNode created =
        body(
            send(
                    post("/api/v1/orders/auec").with(writer()),
                    Map.of(
                        "responsibleOrgUnitId",
                        profit,
                        "requestingOrgUnitId",
                        world.pinned(Pin.OWN_STAFFEL),
                        "handle",
                        "Settlement order",
                        "target",
                        target))
                .andExpect(status().isCreated()));
    return UUID.fromString(created.path("id").asString());
  }

  private UUID fundedOrder(String target) throws Exception {
    UUID id = createOrder(target);
    send(
            post(MONEY + "/provisions", id).with(writer()),
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "amount",
                "600000",
                "bankHandling",
                false,
                "holderVersion",
                0,
                "holderUserId",
                world.user(Actor.EMPLOYEE_DEPOSIT)))
        .andExpect(status().isOk());
    return id;
  }

  private JsonNode linkedRequest(String type) throws Exception {
    var requests =
        body(
            mvc.perform(get("/api/v1/bank/org-units/requests").with(writer()))
                .andExpect(status().isOk()));
    for (JsonNode request : requests) {
      if ("AUEC_ORDER".equals(request.path("purposeKind").asString())) {
        assertThat(request.path("type").asString()).isEqualTo(type);
        return request;
      }
    }
    throw new AssertionError("No linked request was raised for " + type);
  }

  private ResultActions confirm(JsonNode request, Actor staff, UUID custodian) throws Exception {
    return send(
        post("/api/v1/bank/requests/{id}/confirm", request.path("id").asString())
            .with(jwt().jwt(token(staff)).authorities(authorities::convert)),
        Map.of(
            "holderId",
            custodian,
            "version",
            request.path("version").asLong(),
            "ownerApprovalConfirmed",
            true,
            "staffNote",
            "Approval checked"));
  }

  private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> terms)
      throws Exception {
    return mvc.perform(
        request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(terms)));
  }

  private JsonNode body(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
  }

  private RequestPostProcessor writer() {
    return jwt()
        .jwt(token(Actor.MEMBER))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, profit));
  }

  private Jwt token(Actor actor) {
    return Jwt.withTokenValue("linked-settlement-test")
        .header("alg", "none")
        .subject(world.user(actor).toString())
        .claim("preferred_username", world.username(actor))
        .build();
  }
}
