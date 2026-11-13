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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Triggers a patch change with the currency wipe against PostgreSQL (REQ-PATCH-040, -044…046): the
 * aUEC holdings that existed at the live time and are still held become real shortages that name
 * the patch change, the commitments opened before it end, later money stays, and the job-order and
 * Bank trails each record one summary event.
 *
 * <p>Its live time lies in 2000, before every other test's rows; every row it writes is removed
 * afterwards, the patch changes included, because a leftover row would move the merge cutoff.
 */
@SpringBootTest
class PatchChangeCurrencyWipeIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");
  private static final String LABEL = "0.1";
  private static final String MONEY = "/api/v1/orders/{id}/money";
  private static final String FEE_RATE = "operation.transfer_fee_rate";
  private static final PatchChangeOptions CURRENCY_WIPE =
      new PatchChangeOptions(Set.of(), false, true, false);

  @Autowired private WebApplicationContext context;
  @Autowired private PatchChangeTrigger trigger;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private JobOrderRepository orders;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  private final List<UUID> createdOrders = new ArrayList<>();
  private MockMvc mvc;
  private UUID responsible;
  private UUID requester;
  private UUID writer;
  private UUID reader;
  private UUID admin;
  private String feeRate;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    feeRate =
        jdbc.queryForObject(
            "SELECT setting_value FROM system_setting WHERE setting_key = ?",
            String.class,
            FEE_RATE);
    jdbc.update("UPDATE system_setting SET setting_value = '0' WHERE setting_key = ?", FEE_RATE);
    transactions.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString();
      OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
      responsible = squadron("Wipe responsible " + tag, profit);
      requester = squadron("Wipe requester " + tag, null);
      writer = member("wipe-writer-" + tag);
      reader = member("wipe-reader-" + tag);
      admin = account("wipe-admin-" + tag);
    });
  }

  @AfterEach
  void clean() {
    SecurityContextHolder.clearContext();
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", feeRate, FEE_RATE);
    transactions.executeWithoutResult(_ -> {
      List<UUID> patchChanges = jdbc.queryForList("SELECT id FROM patch_change", UUID.class);
      for (UUID patchChange : patchChanges) {
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", patchChange);
        jdbc.update(
            "DELETE FROM bank_audit_event WHERE details LIKE ?",
            "patchChange=" + patchChange + "%");
      }
      jdbc.update("DELETE FROM patch_change");
      for (UUID id : createdOrders) {
        jdbc.update("DELETE FROM job_order_money_commitment_change WHERE order_id = ?", id);
        jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", id);
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", id);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
        orders.deleteById(id);
      }
      orders.flush();
      memberships.deleteById(new OrgUnitMembershipId(writer, requester));
      memberships.deleteById(new OrgUnitMembershipId(reader, requester));
      memberships.flush();
      users.deleteAllById(List.of(writer, reader, admin));
      units.deleteAllById(List.of(responsible, requester));
    });
  }

  @Test
  void theCurrencyWipeBooksTheHoldingsOfTheLiveTimeAsShortagesAndEndsTheCommitments()
      throws Exception {
    UUID wiped = createOrder();
    provide(wiped, "4000000", 0);
    openResponsibleCommitment(wiped, "6000000");
    backdate(wiped);
    provide(wiped, "1000000", 1);
    UUID moved = createOrder();
    String holder = provide(moved, "3000000", 0);
    backdate(moved);
    collect(moved, holder, "2000000");
    UUID untouched = createOrder();
    provide(untouched, "500000", 0);
    signInAdmin();

    PatchChangePreviewDto preview =
        trigger.preview(new PatchChangePreviewRequest(LIVE_AT, CURRENCY_WIPE));
    PatchChangeDto recorded =
        trigger.trigger(
            new TriggerPatchChangeRequest(
                LABEL, PatchKind.FULL_WIPE, LIVE_AT, CURRENCY_WIPE, LABEL));

    List<PatchCountDto> expected =
        List.of(
            new PatchCountDto(PatchCount.BANK_RESET_TOTAL, 0),
            new PatchCountDto(PatchCount.BANK_REQUESTS_CANCELED, 0),
            new PatchCountDto(PatchCount.AUEC_SHORTAGES, 2),
            new PatchCountDto(PatchCount.AUEC_SHORTAGE_TOTAL, 5_000_000),
            new PatchCountDto(PatchCount.AUEC_BANK_ATTEMPTS_CANCELED, 0),
            new PatchCountDto(PatchCount.AUEC_COMMITMENTS_ENDED, 1));
    assertThat(preview.counts()).containsExactlyElementsOf(expected);
    assertThat(recorded.counts()).containsExactlyElementsOf(expected);
    mvc.perform(get(MONEY, wiped).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("5000000"))
        .andExpect(jsonPath("$.holdings").value("1000000"))
        .andExpect(jsonPath("$.shortages").value("4000000"))
        .andExpect(jsonPath("$.history.length()").value(3))
        .andExpect(jsonPath("$.history[2].kind").value("SHORTAGE"))
        .andExpect(jsonPath("$.history[2].amount").value("4000000"))
        .andExpect(jsonPath("$.history[2].patchLabel").value(LABEL))
        .andExpect(jsonPath("$.history[2].actorName").doesNotExist());
    mvc.perform(get("/api/v1/orders/{id}", wiped).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    mvc.perform(get("/api/v1/orders/{id}/participations", wiped).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moneyCommitments[0].outstanding").value("0"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[1].reason").value("CURRENCY_WIPE"))
        .andExpect(jsonPath("$.moneyCommitments[0].changes[1].before").value("6000000"));
    mvc.perform(get(MONEY, moved).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("2000000"))
        .andExpect(jsonPath("$.shortages").value("1000000"));
    mvc.perform(get(MONEY, untouched).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("500000"))
        .andExpect(jsonPath("$.shortages").value("0"));
    assertThat(auditCount("JOB_ORDER_MONEY_CURRENCY_WIPED", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("JOB_ORDER_MONEY_COMMITMENT_CHANGED", wiped)).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_audit_event WHERE event_type = 'WIPE_RESET_EXECUTED'"
                    + " AND details LIKE ?",
                Long.class,
                "patchChange=" + recorded.id() + " liveAt=" + LIVE_AT + "%"))
        .isEqualTo(1L);
  }

  @Test
  void withoutTheCurrencyWipeTheHoldingsStay() throws Exception {
    UUID order = createOrder();
    provide(order, "4000000", 0);
    backdate(order);
    signInAdmin();

    PatchChangeDto recorded =
        trigger.trigger(
            new TriggerPatchChangeRequest(
                LABEL,
                PatchKind.FULL_WIPE,
                LIVE_AT,
                new PatchChangeOptions(Set.of(), false, false, false),
                LABEL));

    assertThat(recorded.counts()).isEmpty();
    mvc.perform(get(MONEY, order).with(reader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value("4000000"))
        .andExpect(jsonPath("$.shortages").value("0"));
    assertThat(auditCount("JOB_ORDER_MONEY_CURRENCY_WIPED", recorded.id())).isZero();
  }

  /**
   * Creates an aUEC order of the responsible unit for the requesting unit.
   *
   * @return the order's id
   * @throws Exception if the request fails
   */
  private @NotNull UUID createOrder() throws Exception {
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
                                "Wipe contact",
                                "target",
                                "10000000"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID order = UUID.fromString(json.readTree(created).get("id").asString());
    createdOrders.add(order);
    return order;
  }

  /**
   * Records the reader's own Bank-free provision.
   *
   * @param order the order
   * @param amount the whole aUEC
   * @param holderVersion the reader's holder revision the form saw
   * @return the reader's holder id
   * @throws Exception if the request fails
   */
  private @NotNull String provide(@NotNull UUID order, @NotNull String amount, long holderVersion)
      throws Exception {
    String body =
        mvc.perform(
                post(MONEY + "/provisions", order)
                    .with(reader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "operationId",
                                UUID.randomUUID(),
                                "amount",
                                amount,
                                "bankHandling",
                                false,
                                "holderVersion",
                                holderVersion))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("holderId").asString();
  }

  /**
   * Moves money from a holder to a newly named collector without the Bank.
   *
   * @param order the order
   * @param holder the source holder
   * @param amount the whole aUEC
   * @throws Exception if the request fails
   */
  private void collect(@NotNull UUID order, @NotNull String holder, @NotNull String amount)
      throws Exception {
    var body = new HashMap<String, Object>();
    body.put("operationId", UUID.randomUUID());
    body.put("kind", "COLLECTION");
    body.put("sourceHolderId", holder);
    body.put("sourceVersion", 1);
    body.put("amount", amount);
    body.put("rate", "0");
    body.put("bankHandling", false);
    body.put("destinationName", "Wipe collector");
    body.put("destinationVersion", 0);
    mvc.perform(
            post(MONEY + "/movements", order)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /**
   * Opens the responsible unit's commitment.
   *
   * @param order the order
   * @param amount the committed whole aUEC
   * @throws Exception if the request fails
   */
  private void openResponsibleCommitment(@NotNull UUID order, @NotNull String amount)
      throws Exception {
    mvc.perform(
            put("/api/v1/orders/{id}/participations/{unitId}/money-commitment", order, responsible)
                .with(writer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("amount", amount))))
        .andExpect(status().isNoContent());
  }

  /**
   * Moves every money fact and commitment the order holds so far to before the live time.
   *
   * @param order the order
   */
  private void backdate(@NotNull UUID order) {
    jdbc.update(
        "UPDATE job_order_money_operation SET recorded_at = ? WHERE order_id = ?",
        Timestamp.from(BEFORE),
        order);
    jdbc.update(
        "UPDATE job_order_money_commitment SET opened_at = ? WHERE order_id = ?",
        Timestamp.from(BEFORE),
        order);
  }

  /** Signs the admin in for the trigger. */
  private void signInAdmin() {
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(admin.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  /**
   * Counts the audit events of a type about a subject.
   *
   * @param type the event type
   * @param subject the subject
   * @return the count
   */
  private long auditCount(@NotNull String type, @NotNull UUID subject) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
            Long.class,
            type,
            subject);
    return count == null ? 0 : count;
  }

  private RequestPostProcessor writer() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private RequestPostProcessor reader() {
    return jwt()
        .jwt(token -> token.subject(reader.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private UUID member(String name) {
    UUID user = account(name);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user, requester));
    membership.setUser(users.findById(user).orElseThrow());
    membership.setKind(units.findById(requester).orElseThrow().getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
    return user;
  }

  private UUID account(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    user.getRoles().add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
    return users.saveAndFlush(user).getId();
  }

  private UUID squadron(String name, OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit).getId();
  }
}
