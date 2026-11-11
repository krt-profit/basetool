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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.privacy.api.HandleAnonymisationService;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService.KeycloakPresenceCheck;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises order money after account privacy operations against real PostgreSQL. */
@SpringBootTest
class JobOrderMoneyPrivacyIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;
  @Autowired private UserAccountMergeService accountMerge;
  @Autowired private HandleAnonymisationService anonymisation;
  @Autowired private UserDeletionService accountDeletion;

  private MockMvc mvc;
  private UUID responsible;
  private UUID requester;
  private UUID writer;
  private UUID unitless;
  private String unitlessName;
  private UUID survivor;
  private UUID createdOrder;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    transactions.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString();
      OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
      responsible = squadron("Money responsible " + tag, profit).getId();
      requester = squadron("Money requester " + tag, null).getId();
      writer = member("money-writer-" + tag);
      unitlessName = "money-linked-" + tag;
      unitless = account(unitlessName).getId();
      survivor = account("money-survivor-" + tag).getId();
    });
  }

  @AfterEach
  void clean() {
    transactions.executeWithoutResult(_ -> {
      if (createdOrder != null) {
        jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", createdOrder);
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", createdOrder);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", createdOrder);
        orders.deleteById(createdOrder);
      }
      orders.flush();
      memberships.deleteById(new OrgUnitMembershipId(writer, requester));
      memberships.flush();
      jdbc.update(
          "DELETE FROM audit_event WHERE actor_user_id = ? OR target_user_id IN (?, ?, ?)",
          writer,
          writer,
          unitless,
          survivor);
      users.deleteAllById(List.of(writer, unitless, survivor));
      units.deleteAllById(List.of(responsible, requester));
    });
  }

  @Test
  void grantedErasureRedactsHolderAndSubmittedNameAndPreservesRecovery() throws Exception {
    UUID order = createOrder();
    UUID linkedOperation = UUID.randomUUID();
    UUID ownOperation = UUID.randomUUID();
    UUID namedOperation = UUID.randomUUID();
    String originalHolder = provisionFor(order, linkedOperation, "10", unitless);
    provision(
            order,
            signedInMember(unitless),
            Map.of(
                "operationId",
                ownOperation,
                "amount",
                "3",
                "bankHandling",
                false,
                "holderVersion",
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actorName").value(unitlessName));
    Map<String, ?> namedTerms =
        Map.of(
            "operationId",
            namedOperation,
            "amount",
            "4",
            "bankHandling",
            false,
            "holderVersion",
            0,
            "holderName",
            unitlessName.toUpperCase(Locale.ROOT));
    String namedHolder =
        json.readTree(
                provision(order, writer(), namedTerms)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray())
            .path("holderId")
            .asString();
    changeHolder(
            order,
            namedHolder,
            writer(),
            Map.of("kind", "NAME", "version", 1, "name", unitlessName))
        .andExpect(status().isNoContent());
    ResultActions beforeErasure = personSearch(unitlessName);
    String alternateName = "E2E recorded alias " + UUID.randomUUID();
    UUID aliasOperation = UUID.randomUUID();
    String aliasHolder = provisionNamed(order, aliasOperation, "2", alternateName);
    changeHolder(
            order,
            aliasHolder,
            writer(),
            Map.of(
                "kind",
                "MERGE",
                "version",
                1,
                "targetHolderId",
                originalHolder,
                "targetVersion",
                2))
        .andExpect(status().isNoContent());
    personSearch(alternateName)
        .andExpect(jsonPath("$.hits[*].column", hasItem("selected_accountless_name")))
        .andExpect(jsonPath("$.hits[*].column", hasItem("previous_name")));

    transactions.executeWithoutResult(_ -> {
      anonymisation.anonymise(unitless, List.of(unitlessName));
      users.findById(unitless).orElseThrow().setInKeycloak(false);
      accountDeletion.deleteUser(
          unitless, KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    });

    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, ownOperation)
                .with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(originalHolder))
        .andExpect(jsonPath("$.holderName").value(HandleAnonymisation.SENTINEL))
        .andExpect(jsonPath("$.actorName").doesNotExist());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, namedOperation)
                .with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderName").value(HandleAnonymisation.SENTINEL))
        .andExpect(jsonPath("$.amount").value("4"));
    provision(order, writer(), namedTerms)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail").value("This operation was submitted with different terms."));
    beforeErasure
        .andExpect(jsonPath("$.hits[*].column", hasItem("accountless_name")))
        .andExpect(jsonPath("$.hits[*].column", hasItem("selected_accountless_name")))
        .andExpect(jsonPath("$.hits[*].column", hasItem("previous_name")))
        .andExpect(jsonPath("$.hits[*].column", hasItem("name")));
    personSearch(unitlessName)
        .andExpect(jsonPath("$.hits[*].column", not(hasItem("accountless_name"))))
        .andExpect(jsonPath("$.hits[*].column", not(hasItem("selected_accountless_name"))))
        .andExpect(jsonPath("$.hits[*].column", not(hasItem("previous_name"))))
        .andExpect(jsonPath("$.hits[*].table", not(hasItem("job_order_money_holder_change"))));
    personSearch(alternateName)
        .andExpect(jsonPath("$.hits[*].column", not(hasItem("selected_accountless_name"))))
        .andExpect(jsonPath("$.hits[*].column", not(hasItem("previous_name"))));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, aliasOperation)
                .with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(aliasHolder))
        .andExpect(jsonPath("$.holderName").value(HandleAnonymisation.SENTINEL));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("19"))
        .andExpect(jsonPath("$.holdings").value("19"))
        .andExpect(jsonPath("$.history.length()").value(4))
        .andExpect(jsonPath("$.holderIdentityHistory[0].name").value(HandleAnonymisation.SENTINEL))
        .andExpect(
            jsonPath("$.holderIdentityHistory[0].previousName")
                .value(HandleAnonymisation.SENTINEL));
  }

  @Test
  void personalExportContainsOnlyTheSubjectHolderAndTheirRelatedProvisions() throws Exception {
    UUID order = createOrder();
    UUID ownOperation = UUID.randomUUID();
    UUID otherOperation = UUID.randomUUID();
    String ownHolder = provisionFor(order, ownOperation, "9007199254740993", unitless);
    provisionFor(order, otherOperation, "4", survivor);

    JsonNode export = exportFor(unitless);
    JsonNode holder = singleExportRow(export, "jobOrderMoneyHolders");
    assertThat(holder.path("holder_id").asString()).isEqualTo(ownHolder);
    JsonNode operation = singleExportRow(export, "jobOrderMoneyOperations");
    assertThat(operation.path("operation_id").asString()).isEqualTo(ownOperation.toString());
    assertThat(operation.path("amount").asString()).isEqualTo("9007199254740993");
  }

  @Test
  void explicitHolderMaintenancePreservesHistoryAndRequiresResponsibleAuthority() throws Exception {
    UUID order = createOrder();
    UUID original = UUID.randomUUID();
    UUID duplicate = UUID.randomUUID();
    String main = provisionNamed(order, original, "10", unitlessName);
    String sameName = provisionNamed(order, duplicate, "4", unitlessName);
    String designation = recipient(order, Map.of("holderId", main));
    UUID handover = purposeHandover(order, main, designation);
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isForbidden());

    changeHolder(order, main, writer(), Map.of("kind", "LINK", "version", 2, "userId", unitless))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isOk());
    String correctedName = "Corrected " + unitlessName;
    changeHolder(
            order,
            sameName,
            signedInMember(unitless),
            Map.of("kind", "NAME", "version", 1, "name", correctedName))
        .andExpect(status().isForbidden());
    changeHolder(
            order, sameName, writer(), Map.of("kind", "NAME", "version", 1, "name", correctedName))
        .andExpect(status().isNoContent());
    changeHolder(
            order,
            sameName,
            writer(),
            Map.of("kind", "MERGE", "version", 2, "targetHolderId", main, "targetVersion", 3))
        .andExpect(status().isNoContent());

    assertMergedSelectionRefused(order, "holderId", sameName);
    ageHolderIdentityAudit(order);
    mvc.perform(
            delete("/api/v1/audit/JOB_ORDER").param("before", "2000-02-01T00:00:00Z").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(3));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(unitless)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("14"))
        .andExpect(jsonPath("$.holdings").value("11"))
        .andExpect(jsonPath("$.netHandedOver").value("3"))
        .andExpect(jsonPath("$.holders.length()").value(1))
        .andExpect(jsonPath("$.holders[0].id").value(main))
        .andExpect(jsonPath("$.holders[0].userId").value(unitless.toString()))
        .andExpect(
            jsonPath(
                "$.holderIdentityHistory[*].kind", containsInAnyOrder("LINK", "NAME", "MERGE")))
        .andExpect(
            jsonPath("$.holderIdentityHistory[?(@.kind == 'NAME')].name", hasItem(correctedName)))
        .andExpect(
            jsonPath(
                "$.history[*].operationId",
                containsInAnyOrder(
                    original.toString(), duplicate.toString(), handover.toString())));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, duplicate)
                .with(signedInMember(unitless)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(sameName))
        .andExpect(jsonPath("$.holderName").value(unitlessName))
        .andExpect(jsonPath("$.amount").value("4"));
  }

  @Test
  void correctingAFalseHolderLinkRevokesReadershipAndRetainsTheHandover() throws Exception {
    UUID order = createOrder();
    UUID original = UUID.randomUUID();
    String holder = provisionFor(order, original, "10", unitless);
    String designation = recipient(order, Map.of("holderId", holder));
    UUID handover = purposeHandover(order, holder, designation);
    String returnDestination = provisionFor(order, UUID.randomUUID(), "1", writer);
    mvc.perform(
            delete("/api/v1/orders/{id}/money/recipients/{recipient}", order, designation)
                .with(writer()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isOk());

    changeHolder(
            order,
            holder,
            writer(),
            Map.of("kind", "CORRECT_LINK", "version", 2, "userId", survivor))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, handover)
                .with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, handover)
                .with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(holder))
        .andExpect(jsonPath("$.recipientId").value(designation))
        .andExpect(jsonPath("$.amount").value("3"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.returnableRecipientIds.length()").value(1))
        .andExpect(jsonPath("$.returnableRecipientIds", not(hasItem(designation))));
    requesterReturn(order, designation, returnDestination, 1, writer())
        .andExpect(status().isConflict());
    assertThat(exportFor(unitless).toString())
        .doesNotContain(original.toString(), handover.toString());
    JsonNode export = exportFor(survivor);
    JsonNode change = singleExportRow(export, "jobOrderMoneyHolderChanges");
    assertThat(change.path("kind").asString()).isEqualTo("CORRECT_LINK");
    assertThat(change.path("holder_id").asString()).isEqualTo(holder);
  }

  @Test
  void correctingDirectRequesterDesignationRevokesFalseHistoricalReadGrant() throws Exception {
    UUID order = createOrder();
    String source = provisionFor(order, UUID.randomUUID(), "10", writer);
    String falseRecipient = recipient(order, Map.of("userId", unitless));
    UUID handover = requesterHandover(order, source, falseRecipient, "HANDOVER", "0.005");
    mvc.perform(
            delete("/api/v1/orders/{id}/money/recipients/{recipient}", order, falseRecipient)
                .with(writer()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isOk());

    String correctPath = "/api/v1/orders/{id}/money/recipients/{recipient}/identity";
    mvc.perform(
            patch(correctPath, order, falseRecipient)
                .with(signedInMember(unitless))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", survivor))))
        .andExpect(status().isForbidden());
    String corrected =
        json.readTree(
                mvc.perform(
                        patch(correctPath, order, falseRecipient)
                            .with(writer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("userId", survivor))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.correctsId").value(falseRecipient))
                    .andExpect(jsonPath("$.userId").value(survivor.toString()))
                    .andExpect(jsonPath("$.active").value(false))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray())
            .path("id")
            .asString();

    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/orders").param("type", "AUEC").with(signedInMember(unitless)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", not(hasItem(order.toString()))));
    mvc.perform(get("/api/v1/orders").param("type", "AUEC").with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", hasItem(order.toString())));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, handover)
                .with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, handover)
                .with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recipientId").value(falseRecipient))
        .andExpect(
            jsonPath("$.recipientName")
                .value(users.findById(survivor).orElseThrow().getEffectiveName()))
        .andExpect(jsonPath("$.amount").value("3"));

    assertThat(exportFor(unitless).toString()).doesNotContain(handover.toString());
    JsonNode received = singleExportRow(exportFor(survivor), "jobOrderMoneyOperations");
    assertThat(received.path("operation_id").asString()).isEqualTo(handover.toString());

    requesterReturn(order, falseRecipient, source, 2, writer()).andExpect(status().isConflict());
    requesterReturn(order, corrected, source, 2, signedInMember(survivor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("2"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("10"))
        .andExpect(jsonPath("$.holdings").value("9"))
        .andExpect(jsonPath("$.netHandedOver").value("1"));
  }

  private ResultActions requesterReturn(
      UUID order,
      String designation,
      String destination,
      long destinationVersion,
      RequestPostProcessor actor)
      throws Exception {
    return mvc.perform(
        post("/api/v1/orders/{id}/money/movements/settlement", order)
            .with(actor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "operationId",
                        UUID.randomUUID(),
                        "kind",
                        "REQUESTER_RETURN",
                        "recipientId",
                        designation,
                        "destinationHolderId",
                        destination,
                        "destinationVersion",
                        destinationVersion,
                        "targetVersion",
                        0,
                        "amount",
                        "2",
                        "rate",
                        "0.005",
                        "bankHandling",
                        false))));
  }

  private UUID purposeHandover(UUID order, String holder, String designation) throws Exception {
    return requesterHandover(order, holder, designation, "PURPOSE_HANDOVER", "0");
  }

  private UUID requesterHandover(
      UUID order, String holder, String designation, String kind, String rate) throws Exception {
    UUID handover = UUID.randomUUID();
    mvc.perform(
            post("/api/v1/orders/{id}/money/movements", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "operationId", handover,
                            "kind", kind,
                            "sourceHolderId", holder,
                            "sourceVersion", 1,
                            "targetVersion", 0,
                            "recipientId", designation,
                            "amount", "3",
                            "rate", rate,
                            "bankHandling", false))))
        .andExpect(status().isOk());
    return handover;
  }

  private ResultActions changeHolder(
      UUID order, String holder, RequestPostProcessor user, Map<String, ?> change)
      throws Exception {
    return mvc.perform(
        patch("/api/v1/orders/{id}/money/holders/{holder}/identity", order, holder)
            .with(user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(change)));
  }

  private String provisionNamed(UUID order, UUID operation, String amount, String name)
      throws Exception {
    return json.readTree(
            provision(
                    order,
                    writer(),
                    Map.of(
                        "operationId", operation,
                        "amount", amount,
                        "bankHandling", false,
                        "holderVersion", 0,
                        "holderName", name))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray())
        .path("holderId")
        .asString();
  }

  private JsonNode exportFor(UUID subject) throws Exception {
    return json.readTree(
        mvc.perform(
                get("/api/v1/users/me/export")
                    .with(signedInMember(subject))
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private static JsonNode singleExportRow(JsonNode export, String sectionKey) {
    for (JsonNode section : export.path("sections")) {
      if (sectionKey.equals(section.path("key").asString())) {
        JsonNode rows = section.path("rows");
        assertThat(rows.isArray()).as(sectionKey).isTrue();
        assertThat(rows.size()).as(sectionKey).isEqualTo(1);
        return rows.get(0);
      }
    }
    throw new AssertionError("Missing export section " + sectionKey);
  }

  private void ageHolderIdentityAudit(UUID order) {
    jdbc.update(
        """
        UPDATE audit_event SET occurred_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
        WHERE subject_id = ? AND event_type = 'JOB_ORDER_MONEY_HOLDER_IDENTITY_CHANGED'
        """,
        order);
  }

  private RequestPostProcessor admin() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private ResultActions personSearch(String query) throws Exception {
    return mvc.perform(get("/api/v1/users/admin/person-search").param("q", query).with(admin()))
        .andExpect(status().isOk());
  }

  @Test
  void mergingAccountsWithHoldingsMovesOrderAccessAndPreservesBothProvisions() throws Exception {
    UUID order = createOrder();
    UUID sourceOperation = UUID.randomUUID();
    UUID survivorOperation = UUID.randomUUID();
    String sourceHolder = provisionFor(order, sourceOperation, "9007199254740993", unitless);
    String survivorHolder = provisionFor(order, survivorOperation, "7", survivor);
    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isOk());

    accountMerge.merge(unitless, survivor, writer);

    mvc.perform(get("/api/v1/orders/{id}", order).with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    assertMergedSelectionRefused(order, "holderId", sourceHolder);
    assertMergedSelectionRefused(order, "holderUserId", unitless.toString());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("9007199254741000"))
        .andExpect(jsonPath("$.holdings").value("9007199254741000"))
        .andExpect(jsonPath("$.holders.length()").value(1))
        .andExpect(jsonPath("$.holders[0].id").value(survivorHolder))
        .andExpect(jsonPath("$.holders[0].userId").value(survivor.toString()))
        .andExpect(
            jsonPath(
                "$.history[*].operationId",
                containsInAnyOrder(sourceOperation.toString(), survivorOperation.toString())));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, sourceOperation)
                .with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(sourceHolder))
        .andExpect(jsonPath("$.amount").value("9007199254740993"));
  }

  @Test
  void recipientMergeAndErasurePreserveTheBookedPurposeAndRemoveOldPersonalAccess()
      throws Exception {
    UUID order = createOrder();
    String holder = provisionFor(order, UUID.randomUUID(), "10", writer);
    String linked = recipient(order, Map.of("userId", unitless));
    String accountless = recipient(order, Map.of("name", unitlessName));
    assertThat(linked).isNotEqualTo(accountless);
    String namedHolder =
        json.readTree(
                provision(
                        order,
                        writer(),
                        Map.of(
                            "operationId",
                            UUID.randomUUID(),
                            "holderName",
                            unitlessName,
                            "holderVersion",
                            0,
                            "amount",
                            "1",
                            "bankHandling",
                            false))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray())
            .path("holderId")
            .asString();
    String holderDesignation = recipient(order, Map.of("holderId", namedHolder));
    UUID movement = UUID.randomUUID();
    mvc.perform(
            post("/api/v1/orders/{id}/money/movements", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "operationId",
                            movement,
                            "kind",
                            "HANDOVER",
                            "sourceHolderId",
                            holder,
                            "sourceVersion",
                            1,
                            "targetVersion",
                            0,
                            "recipientId",
                            linked,
                            "amount",
                            "5",
                            "rate",
                            "0.005",
                            "bankHandling",
                            false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recipientId").value(linked));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(unitless)))
        .andExpect(status().isOk());

    accountMerge.merge(unitless, survivor, writer);

    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(unitless)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(survivor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.netHandedOver").value("5"));
    transactions.executeWithoutResult(_ -> {
      anonymisation.anonymise(survivor, List.of(unitlessName));
      users.findById(survivor).orElseThrow().setInKeycloak(false);
      accountDeletion.deleteUser(
          survivor, KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    });
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(signedInMember(survivor)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, movement).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recipientId").value(linked))
        .andExpect(jsonPath("$.recipientName").doesNotExist())
        .andExpect(jsonPath("$.kind").value("HANDOVER"))
        .andExpect(jsonPath("$.amount").value("5"));
    mvc.perform(get("/api/v1/orders/{id}/money", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("6"))
        .andExpect(jsonPath("$.netHandedOver").value("5"))
        .andExpect(jsonPath("$.recipients[*].name", hasItem(HandleAnonymisation.SENTINEL)))
        .andExpect(jsonPath("$.recipients[?(@.active == true)]").isEmpty());
    mvc.perform(
            post("/api/v1/orders/{id}/money/recipients", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("holderId", namedHolder))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders/{id}/money/movements", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "operationId",
                            UUID.randomUUID(),
                            "kind",
                            "HANDOVER",
                            "sourceHolderId",
                            holder,
                            "sourceVersion",
                            2,
                            "targetVersion",
                            0,
                            "recipientId",
                            holderDesignation,
                            "amount",
                            "2",
                            "rate",
                            "0.005",
                            "bankHandling",
                            false))))
        .andExpect(status().isConflict());
  }

  private String recipient(UUID order, Map<String, ?> selection) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/orders/{id}/money/recipients", order)
                    .with(writer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(selection)))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asString();
  }

  private void assertMergedSelectionRefused(UUID order, String reference, String value)
      throws Exception {
    UUID operation = UUID.randomUUID();
    provision(
            order,
            writer(),
            Map.of(
                "operationId",
                operation,
                "amount",
                "19",
                "bankHandling",
                false,
                "holderVersion",
                1,
                reference,
                value))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
    mvc.perform(
            get("/api/v1/orders/{id}/money/operations/{operation}", order, operation)
                .with(writer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MONEY_PROVISION_REFUSED"));
  }

  private String provisionFor(UUID order, UUID operation, String amount, UUID holderUser)
      throws Exception {
    String result =
        provision(
                order,
                writer(),
                Map.of(
                    "operationId", operation,
                    "amount", amount,
                    "bankHandling", false,
                    "holderVersion", 0,
                    "holderUserId", holderUser))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(result).get("holderId").asString();
  }

  private UUID createOrder() throws Exception {
    String created =
        mvc.perform(
                post("/api/v1/orders/auec")
                    .with(writer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "responsibleOrgUnitId",
                                responsible,
                                "requestingOrgUnitId",
                                requester,
                                "handle",
                                "Money contact",
                                "target",
                                "10000000"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("AUEC"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    createdOrder = UUID.fromString(json.readTree(created).get("id").asString());
    return createdOrder;
  }

  private ResultActions provision(UUID order, RequestPostProcessor user, Map<String, ?> terms)
      throws Exception {
    return mvc.perform(
        post("/api/v1/orders/{id}/money/provisions", order)
            .with(user)
            .locale(Locale.ENGLISH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(terms)));
  }

  private RequestPostProcessor writer() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private static RequestPostProcessor signedInMember(UUID id) {
    return jwt()
        .jwt(token -> token.subject(id.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private UUID member(String name) {
    User user = account(name);
    user.getRoles().add(roles.findByNameIgnoreCase(Roles.ADMIN).orElseThrow());
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), requester));
    membership.setUser(user);
    membership.setKind(units.findById(requester).orElseThrow().getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
    return user.getId();
  }

  private User account(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    return users.saveAndFlush(user);
  }

  private Squadron squadron(String name, OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }
}
