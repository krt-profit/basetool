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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres characterisation of the granted handle anonymisation ({@link
 * HandleAnonymisationService#anonymise}, REQ-SEC-062): which handle snapshot of which table is
 * rewritten, which row of another member or another name stays, the per-table counts and the two
 * marker rows with their exact details.
 *
 * <p>The member holds a snapshot in every column the anonymisation covers, matched by id or by
 * spelling; a second member and unrelated names hold rows of the same tables that must not change.
 * The test states the observable result, not the way it is reached, so the steps can move into the
 * owning modules.
 */
@SpringBootTest
@Transactional
class HandleAnonymisationEndStateTest {

  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Autowired private HandleAnonymisationService service;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @MockitoBean private KeycloakService keycloakService;

  private String tag;
  private String callsign;
  private String alias;
  private UUID leaver;
  private UUID stayer;
  private UUID account;

  @Test
  void anonymising_rewritesEveryHandleSnapshotOfTheMember_andNothingElse() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    callsign = "Callsign-" + tag;
    alias = "Alias-" + tag;
    leaver = member("leaver-" + tag);
    stayer = member("stayer-" + tag);
    account = bankAccount();

    UUID labelByCallsign = auditEvent(null, "System", callsign.toUpperCase());
    UUID labelByAlias = auditEvent(null, "System", alias);
    UUID labelByNeither = auditEvent(null, "System", "Prefix " + callsign);
    UUID actorEvent = auditEvent(leaver, callsign, null);
    UUID alreadyErased = auditEvent(leaver, SENTINEL, null);
    UUID strangerEvent = auditEvent(stayer, "Stayer-" + tag, "Unrelated " + tag);

    UUID bankActor = bankAuditEvent(leaver, callsign);
    UUID bankStranger = bankAuditEvent(stayer, "Stayer-" + tag);

    UUID transaction = bankTransaction(leaver, callsign);
    UUID transactionStranger = bankTransaction(stayer, "Stayer-" + tag);

    UUID requestRequesterAndCounterparty =
        bookingRequest(leaver, callsign, stayer, "Stayer-" + tag, leaver, callsign, null, null);
    UUID requestDeciderAndApprover =
        bookingRequest(
            stayer, "Stayer-" + tag, leaver, callsign, stayer, "Stayer-" + tag, leaver, callsign);
    UUID requestStranger =
        bookingRequest(
            stayer, "Stayer-" + tag, stayer, "Stayer-" + tag, stayer, "Stayer-" + tag, null, null);

    UUID holder = bankHolder(leaver, callsign);
    UUID holderStranger = bankHolder(stayer, "Stayer-" + tag);

    UUID orderByCallsign = jobOrder(callsign.toLowerCase());
    UUID orderByAlias = jobOrder(alias.toUpperCase());
    UUID orderStranger = jobOrder("Unrelated-" + tag);

    UUID materialHandover = materialHandover(orderStranger, callsign.toUpperCase());
    UUID materialHandoverStranger = materialHandover(orderStranger, "Unrelated-" + tag);
    UUID itemHandover = itemHandover(orderStranger, alias);
    UUID itemHandoverStranger = itemHandover(orderStranger, "Unrelated-" + tag);
    entityManager.flush();

    HandleAnonymisationService.AnonymisationResult result =
        service.anonymise(leaver, Arrays.asList(callsign, "  ", alias, null));
    entityManager.flush();
    entityManager.clear();

    assertThat(result.activityAudit()).isEqualTo(3);
    assertThat(result.bankAudit()).isEqualTo(1);
    assertThat(result.bankTransactions()).isEqualTo(1);
    assertThat(result.bookingRequests()).isEqualTo(2);
    assertThat(result.bankHolders()).isEqualTo(1);
    assertThat(result.jobOrders()).isEqualTo(2);
    assertThat(result.materialHandovers()).isEqualTo(1);
    assertThat(result.itemHandovers()).isEqualTo(1);
    assertThat(result.total()).isEqualTo(12);

    assertThat(text("audit_event", "subject_label", labelByCallsign)).isEqualTo(SENTINEL);
    assertThat(text("audit_event", "subject_label", labelByAlias)).isEqualTo(SENTINEL);
    assertThat(text("audit_event", "subject_label", labelByNeither))
        .isEqualTo("Prefix " + callsign);
    assertThat(text("audit_event", "actor_handle", actorEvent)).isEqualTo(SENTINEL);
    assertThat(text("audit_event", "actor_handle", alreadyErased)).isEqualTo(SENTINEL);
    assertThat(text("audit_event", "actor_handle", strangerEvent)).isEqualTo("Stayer-" + tag);
    assertThat(text("audit_event", "subject_label", strangerEvent)).isEqualTo("Unrelated " + tag);

    assertThat(text("bank_audit_event", "actor_handle", bankActor)).isEqualTo(SENTINEL);
    assertThat(text("bank_audit_event", "actor_handle", bankStranger)).isEqualTo("Stayer-" + tag);

    assertThat(text("bank_transaction", "counterparty_handle", transaction)).isEqualTo(SENTINEL);
    assertThat(text("bank_transaction", "counterparty_handle", transactionStranger))
        .isEqualTo("Stayer-" + tag);

    assertThat(text("bank_booking_request", "requester_handle", requestRequesterAndCounterparty))
        .isEqualTo(SENTINEL);
    assertThat(text("bank_booking_request", "counterparty_handle", requestRequesterAndCounterparty))
        .isEqualTo(SENTINEL);
    assertThat(text("bank_booking_request", "decider_handle", requestRequesterAndCounterparty))
        .isEqualTo("Stayer-" + tag);
    assertThat(text("bank_booking_request", "requester_handle", requestDeciderAndApprover))
        .isEqualTo("Stayer-" + tag);
    assertThat(text("bank_booking_request", "decider_handle", requestDeciderAndApprover))
        .isEqualTo(SENTINEL);
    assertThat(
            text(
                "bank_booking_request",
                "owner_approval_granted_by_handle",
                requestDeciderAndApprover))
        .isEqualTo(SENTINEL);
    assertThat(text("bank_booking_request", "requester_handle", requestStranger))
        .isEqualTo("Stayer-" + tag);
    assertThat(text("bank_booking_request", "decider_handle", requestStranger))
        .isEqualTo("Stayer-" + tag);

    assertThat(text("bank_holder", "handle", holder)).isEqualTo(SENTINEL);
    assertThat(text("bank_holder", "handle", holderStranger)).isEqualTo("Stayer-" + tag);

    assertThat(text("job_order", "handle", orderByCallsign)).isEqualTo(SENTINEL);
    assertThat(text("job_order", "handle", orderByAlias)).isEqualTo(SENTINEL);
    assertThat(text("job_order", "handle", orderStranger)).isEqualTo("Unrelated-" + tag);

    assertThat(text("job_order_handover", "recipient_handle", materialHandover))
        .isEqualTo(SENTINEL);
    assertThat(text("job_order_handover", "recipient_handle", materialHandoverStranger))
        .isEqualTo("Unrelated-" + tag);
    assertThat(text("job_order_item_handover", "recipient_handle", itemHandover))
        .isEqualTo(SENTINEL);
    assertThat(text("job_order_item_handover", "recipient_handle", itemHandoverStranger))
        .isEqualTo("Unrelated-" + tag);

    assertThat(markers("audit_event"))
        .containsExactly(
            "activityAudit=3 bankAudit=1 bankTransactions=1 bookingRequests=2 bankHolders=1"
                + " jobOrders=2 materialHandovers=1 itemHandovers=1 spellings=2");
    assertThat(markers("bank_audit_event"))
        .containsExactly("bankAudit=1 bankTransactions=1 bookingRequests=2 bankHolders=1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE target_user_id = ?"
                    + " AND details LIKE '%' || ? || '%'",
                Integer.class, leaver, callsign))
        .as("no marker carries the erased name")
        .isZero();
  }

  @Test
  void anonymising_withoutAnySpelling_rewritesOnlyTheIdMatchedColumns() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    callsign = "Callsign-" + tag;
    leaver = member("leaver-" + tag);
    UUID byLabel = auditEvent(null, "System", callsign);
    UUID byActor = auditEvent(leaver, callsign, null);
    UUID holder = bankHolder(leaver, callsign);
    UUID order = jobOrder(callsign);
    entityManager.flush();

    HandleAnonymisationService.AnonymisationResult result =
        service.anonymise(leaver, List.of("   "));
    entityManager.flush();
    entityManager.clear();

    assertThat(result.activityAudit()).isEqualTo(1);
    assertThat(result.bankHolders()).isEqualTo(1);
    assertThat(result.jobOrders()).isZero();
    assertThat(text("audit_event", "actor_handle", byActor)).isEqualTo(SENTINEL);
    assertThat(text("audit_event", "subject_label", byLabel)).isEqualTo(callsign);
    assertThat(text("bank_holder", "handle", holder)).isEqualTo(SENTINEL);
    assertThat(text("job_order", "handle", order)).isEqualTo(callsign);
    assertThat(markers("audit_event"))
        .containsExactly(
            "activityAudit=1 bankAudit=0 bankTransactions=0 bookingRequests=0 bankHolders=1"
                + " jobOrders=0 materialHandovers=0 itemHandovers=0 spellings=0");
    assertThat(markers("bank_audit_event"))
        .containsExactly("bankAudit=0 bankTransactions=0 bookingRequests=0 bankHolders=1");
  }

  private List<String> markers(String table) {
    return jdbc.queryForList(
        "SELECT details FROM "
            + table
            + " WHERE event_type = 'HANDLE_SNAPSHOTS_ANONYMISED' AND target_user_id = ?",
        String.class,
        leaver);
  }

  private String text(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
  }

  private UUID member(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private UUID auditEvent(UUID actor, String actorHandle, String subjectLabel) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_event (id, occurred_at, domain, event_type, actor_user_id,"
            + " actor_handle, subject_label) VALUES (?, now(), 'INVENTORY', 'INVENTORY_ADDED',"
            + " ?, ?, ?)",
        id,
        actor,
        actorHandle,
        subjectLabel);
    return id;
  }

  private UUID bankAuditEvent(UUID actor, String actorHandle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_audit_event (id, occurred_at, actor_user_id, actor_handle, event_type)"
            + " VALUES (?, now(), ?, ?, 'ACCOUNT_CREATED')",
        id,
        actor,
        actorHandle);
    return id;
  }

  private UUID bankAccount() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        id,
        "H" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999),
        "Handle Account " + tag);
    return id;
  }

  private UUID bankTransaction(UUID counterparty, String handle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, created_at, counterparty_user_id,"
            + " counterparty_handle) VALUES (?, 'DEPOSIT', now(), ?, ?)",
        id,
        counterparty,
        handle);
    return id;
  }

  private UUID bookingRequest(
      UUID requester,
      String requesterHandle,
      UUID decider,
      String deciderHandle,
      UUID counterparty,
      String counterpartyHandle,
      UUID approver,
      String approverHandle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_booking_request (id, account_id, type, amount, status, requested_by,"
            + " requester_handle, decided_by, decider_handle, counterparty_user_id,"
            + " counterparty_handle, owner_approval_granted_by,"
            + " owner_approval_granted_by_handle) VALUES (?, ?, 'DEPOSIT', 10, 'REJECTED', ?, ?,"
            + " ?, ?, ?, ?, ?, ?)",
        id,
        account,
        requester,
        requesterHandle,
        decider,
        deciderHandle,
        counterparty,
        counterpartyHandle,
        approver,
        approverHandle);
    return id;
  }

  private UUID bankHolder(UUID user, String handle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_holder (id, version, created_at, updated_at, user_id, handle)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        id,
        user,
        handle);
    return id;
  }

  private UUID jobOrder(String handle) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    SpecialCommand sk = new SpecialCommand();
    sk.setName("Handle-SK-" + unique);
    sk.setShorthand("H" + unique);
    sk.setProfitEligible(true);
    specialCommandRepository.save(sk);
    Material material = new Material();
    material.setName("Handle Ore " + unique);
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

  private UUID materialHandover(UUID order, String recipient) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_handover (id, job_order_id, handover_time, recipient_handle,"
            + " created_at, updated_at, version) VALUES (?, ?, now(), ?, now(), now(), 0)",
        id,
        order,
        recipient);
    return id;
  }

  private UUID itemHandover(UUID order, String recipient) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_item_handover (id, job_order_id, handover_time, recipient_handle,"
            + " created_at, updated_at, version) VALUES (?, ?, now(), ?, now(), now(), 0)",
        id,
        order,
        recipient);
    return id;
  }
}
