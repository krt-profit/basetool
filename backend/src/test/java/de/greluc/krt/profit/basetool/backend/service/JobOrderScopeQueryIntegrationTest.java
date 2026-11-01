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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for the visibility scope of {@link JobOrderRepository#findScopedJobOrders}
 * against the Postgres test container: orders of two squadrons (private) and one Spezialkommando
 * (public) are queried with each caller class's {@link ScopePredicate}. Assertions check only the
 * freshly created ids, since the container is shared.
 */
@SpringBootTest
class JobOrderScopeQueryIntegrationTest {

  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private static final List<JobOrderStatus> ALL_STATUSES = List.of(JobOrderStatus.values());
  private static final PageRequest ALL_ROWS = PageRequest.of(0, 10_000);
  private static final UUID NIL = new UUID(0L, 0L);

  private UUID squadronAId;
  private UUID squadronBId;
  private UUID skId;
  private UUID orderRespA;
  private UUID orderRespB;
  private UUID orderRespSk;

  @BeforeEach
  void seed() {
    transactionTemplate.executeWithoutResult(status -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      Squadron sqA = newSquadron("Scope-A-" + tag, "A" + tag);
      Squadron sqB = newSquadron("Scope-B-" + tag, "B" + tag);
      SpecialCommand sk = newSpecialCommand("Scope-SK-" + tag, "S" + tag);
      squadronAId = sqA.getId();
      squadronBId = sqB.getId();
      skId = sk.getId();

      orderRespA = newOrder(sqA, sqA).getId();
      orderRespB = newOrder(sqB, sqA).getId();
      orderRespSk = newOrder(sk, sqA).getId();
    });
  }

  @Test
  void memberOfSquadronA_seesOwnPrivateAndAllSk_butNotForeignPrivate() {
    Set<UUID> visible = visibleIds(new ScopePredicate(false, null, Set.of(squadronAId)));

    assertThat(visible).contains(orderRespA, orderRespSk);
    assertThat(visible).doesNotContain(orderRespB);
  }

  @Test
  void adminWithoutPin_seesEveryOrder() {
    Set<UUID> visible = visibleIds(new ScopePredicate(true, null, Set.of()));

    assertThat(visible).contains(orderRespA, orderRespB, orderRespSk);
  }

  @Test
  void adminPinnedToSquadronA_seesPinnedPrivateAndAllSk_butNotForeignPrivate() {
    Set<UUID> visible = visibleIds(new ScopePredicate(false, squadronAId, Set.of()));

    assertThat(visible).contains(orderRespA, orderRespSk);
    assertThat(visible).doesNotContain(orderRespB);
  }

  @Test
  void squadronDisplayFilter_narrowsWithinScope() {
    Set<UUID> visible =
        jobOrderRepository
            .findScopedJobOrders(
                ALL_STATUSES,
                false,
                Set.of(squadronBId),
                false,
                Set.of(new UUID(0L, 0L)),
                true,
                null,
                Set.of(),
                new UUID(0L, 0L),
                ALL_ROWS)
            .stream()
            .map(JobOrder::getId)
            .collect(Collectors.toSet());

    assertThat(visible).contains(orderRespB);
    assertThat(visible).doesNotContain(orderRespA, orderRespSk);
  }

  @Test
  void v130_tightensResponsibleOrgUnitToNotNull() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String nullable =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'job_order' AND"
                + " column_name = 'responsible_org_unit_id'",
            String.class);
    assertThat(nullable).isEqualTo("NO");
  }

  @Test
  void countProfitEligibleByIdIn_countsProfitEligibleAcrossBothKinds() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron profit = new Squadron();
    profit.setName("Profit-" + tag);
    profit.setShorthand("P" + tag);
    profit.setProfitEligible(true);
    Squadron nonProfit = new Squadron();
    nonProfit.setName("NonProfit-" + tag);
    nonProfit.setShorthand("N" + tag);
    SpecialCommand profitSk = new SpecialCommand();
    profitSk.setName("ProfitSK-" + tag);
    profitSk.setShorthand("X" + tag);
    profitSk.setProfitEligible(true);
    UUID profitId = squadronRepository.save(profit).getId();
    UUID nonProfitId = squadronRepository.save(nonProfit).getId();
    UUID profitSkId = specialCommandRepository.save(profitSk).getId();

    assertThat(
            orgUnitRepository.countProfitEligibleByIdIn(Set.of(profitId, nonProfitId, profitSkId)))
        .isEqualTo(2L);
    assertThat(orgUnitRepository.countProfitEligibleByIdIn(Set.of(nonProfitId))).isZero();
    assertThat(orgUnitRepository.countProfitEligibleByIdIn(Set.of(profitSkId))).isEqualTo(1L);
  }

  @Test
  void processingFilter_keepsOnlyOrdersTheGivenUnitsProcess() {
    Set<UUID> visible =
        processingIds(
            new ScopePredicate(false, null, Set.of(squadronAId)), Set.of(squadronAId, skId));

    assertThat(visible).contains(orderRespA, orderRespSk);
    assertThat(visible).doesNotContain(orderRespB);
  }

  @Test
  void processingFilter_narrowsToOneUnitAmongTheCallersOwn() {
    Set<UUID> visible =
        processingIds(new ScopePredicate(false, null, Set.of(squadronAId)), Set.of(squadronAId));

    assertThat(visible).contains(orderRespA);
    assertThat(visible).doesNotContain(orderRespB, orderRespSk);
  }

  @Test
  void processingFilter_neverWidensBeyondTheScope() {
    Set<UUID> visible =
        processingIds(new ScopePredicate(false, null, Set.of(squadronAId)), Set.of(squadronBId));

    assertThat(visible).doesNotContain(orderRespA, orderRespB, orderRespSk);
  }

  @Test
  void processingFilter_dropsOrdersOfANonProfitEligibleUnit() {
    UUID[] ids = new UUID[2];
    transactionTemplate.executeWithoutResult(_ -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      Squadron nonProfit = newSquadron("Scope-N-" + tag, "N" + tag);
      nonProfit.setProfitEligible(false);
      squadronRepository.save(nonProfit);
      ids[0] = nonProfit.getId();
      ids[1] = newOrder(nonProfit, nonProfit).getId();
    });

    Set<UUID> visible = processingIds(new ScopePredicate(true, null, Set.of()), Set.of(ids[0]));

    assertThat(visible).doesNotContain(ids[1]);
    assertThat(visibleIds(new ScopePredicate(true, null, Set.of()))).contains(ids[1]);
  }

  @Test
  void assigneeGrant_inAllNotInToProcess() {
    UUID assigneeId = assignNewUser(orderRespB);
    ScopePredicate memberOfA = new ScopePredicate(false, null, Set.of(squadronAId));

    assertThat(visibleIds(memberOfA, assigneeId)).contains(orderRespB);
    assertThat(processingIds(memberOfA, Set.of(squadronAId, squadronBId), assigneeId))
        .doesNotContain(orderRespB);
  }

  @Test
  void assigneeGrant_opensTheOrderWithoutAnyMembership() {
    UUID assigneeId = assignNewUser(orderRespB);

    assertThat(visibleIds(new ScopePredicate(false, null, Set.of()), assigneeId))
        .contains(orderRespB)
        .doesNotContain(orderRespA);
  }

  @Test
  void assigneeGrant_isPersonal() {
    assignNewUser(orderRespB);
    UUID unitMateId = newUser().getId();

    assertThat(visibleIds(new ScopePredicate(false, null, Set.of(squadronAId)), unitMateId))
        .doesNotContain(orderRespB);
  }

  @Test
  void assigneeGrant_endsWithTheEntry() {
    UUID assigneeId = assignNewUser(orderRespB);
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(orderRespB).orElseThrow();
      order.getAssignees().clear();
      jobOrderRepository.saveAndFlush(order);
    });

    assertThat(visibleIds(new ScopePredicate(false, null, Set.of(squadronAId)), assigneeId))
        .doesNotContain(orderRespB);
  }

  @Test
  void assignedToMe_listsOnlyTheOrdersOfTheCallersOwnEntries() {
    UUID assigneeId = assignNewUser(orderRespB);
    assign(assigneeId, orderRespSk);

    assertThat(assignedIds(assigneeId, ALL_STATUSES))
        .containsExactlyInAnyOrder(orderRespB, orderRespSk)
        .doesNotContain(orderRespA);
  }

  @Test
  void assignedToMe_showsNothingForACallerWithoutEntries() {
    assignNewUser(orderRespB);

    assertThat(assignedIds(newUser().getId(), ALL_STATUSES)).isEmpty();
  }

  @Test
  void assignedToMe_appliesTheStatusFilterAndCountsTheWholeResult() {
    UUID assigneeId = assignNewUser(orderRespB);
    assign(assigneeId, orderRespA);

    Page<JobOrder> open =
        jobOrderRepository.findAssignedJobOrders(
            List.of(JobOrderStatus.OPEN), assigneeId, PageRequest.of(0, 1));
    Page<JobOrder> completed =
        jobOrderRepository.findAssignedJobOrders(
            List.of(JobOrderStatus.COMPLETED), assigneeId, ALL_ROWS);

    assertThat(open.getContent()).hasSize(1);
    assertThat(open.getTotalElements()).isEqualTo(2);
    assertThat(completed.getTotalElements()).isZero();
  }

  @Test
  void assignedToMe_endsWithTheEntry() {
    UUID assigneeId = assignNewUser(orderRespB);
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(orderRespB).orElseThrow();
      order.getAssignees().clear();
      jobOrderRepository.saveAndFlush(order);
    });

    assertThat(assignedIds(assigneeId, ALL_STATUSES)).doesNotContain(orderRespB);
  }

  @Test
  void existsByAssigneesUserId_isTrueOnlyWhileTheUserHoldsAnEntry() {
    UUID assigneeId = assignNewUser(orderRespB);

    assertThat(jobOrderRepository.existsByAssigneesUserId(assigneeId)).isTrue();
    assertThat(jobOrderRepository.existsByAssigneesUserId(newUser().getId())).isFalse();
  }

  @Test
  void numberPrefix_findsTheScopedOrderByItsNumber() {
    ScopePredicate memberOfA = new ScopePredicate(false, null, Set.of(squadronAId));

    assertThat(numberHits(numberOf(orderRespA), true, memberOfA, NIL)).contains(orderRespA);
    assertThat(numberHits(numberOf(orderRespSk), true, memberOfA, NIL)).contains(orderRespSk);
    assertThat(numberHits(numberOf(orderRespB), true, memberOfA, NIL)).doesNotContain(orderRespB);
  }

  @Test
  void numberPrefix_matchesAnOrderWhoseNumberStartsWithTheDigits() {
    String number = numberOf(orderRespA);
    ScopePredicate admin = new ScopePredicate(true, null, Set.of());

    assertThat(numberHits(number.substring(0, 1), true, admin, NIL)).contains(orderRespA);
    assertThat(numberHits(number + "9", true, admin, NIL)).doesNotContain(orderRespA);
  }

  @Test
  void numberPrefix_withoutTheOrderWorkflowStillFindsAssignedOrders() {
    UUID assigneeId = assignNewUser(orderRespB);
    ScopePredicate none = new ScopePredicate(false, null, Set.of());

    assertThat(numberHits(numberOf(orderRespB), false, none, assigneeId)).contains(orderRespB);
    assertThat(numberHits(numberOf(orderRespSk), false, none, assigneeId))
        .doesNotContain(orderRespSk);
  }

  @Test
  void numberPrefix_assigneeGrantIsPersonal() {
    assignNewUser(orderRespB);
    UUID unitMateId = newUser().getId();

    assertThat(
            numberHits(
                numberOf(orderRespB),
                true,
                new ScopePredicate(false, null, Set.of(squadronAId)),
                unitMateId))
        .doesNotContain(orderRespB);
  }

  private String numberOf(UUID orderId) {
    return String.valueOf(jobOrderRepository.findById(orderId).orElseThrow().getDisplayId());
  }

  private Set<UUID> numberHits(String prefix, boolean scoped, ScopePredicate scope, UUID callerId) {
    return jobOrderRepository
        .findVisibleByNumberPrefix(
            prefix,
            scoped,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds().isEmpty() ? Set.of(NIL) : scope.memberOrgUnitIds(),
            callerId,
            ALL_ROWS)
        .stream()
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private void assign(UUID userId, UUID orderId) {
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(orderId).orElseThrow();
      order.addAssignee(
          JobOrderAssignee.builder().user(userRepository.getReferenceById(userId)).build());
      jobOrderRepository.saveAndFlush(order);
    });
  }

  private Set<UUID> assignedIds(UUID callerId, List<JobOrderStatus> statuses) {
    return jobOrderRepository.findAssignedJobOrders(statuses, callerId, ALL_ROWS).stream()
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private UUID assignNewUser(UUID orderId) {
    User user = newUser();
    transactionTemplate.executeWithoutResult(_ -> {
      JobOrder order = jobOrderRepository.findById(orderId).orElseThrow();
      order.addAssignee(JobOrderAssignee.builder().user(user).build());
      jobOrderRepository.saveAndFlush(order);
    });
    return user.getId();
  }

  private User newUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("scope-assignee-" + UUID.randomUUID().toString().substring(0, 8));
    return userRepository.save(user);
  }

  private Set<UUID> processingIds(ScopePredicate scope, Set<UUID> processingOrgUnitIds) {
    return processingIds(scope, processingOrgUnitIds, NIL);
  }

  private Set<UUID> processingIds(
      ScopePredicate scope, Set<UUID> processingOrgUnitIds, UUID callerId) {
    return jobOrderRepository
        .findScopedJobOrders(
            ALL_STATUSES,
            true,
            Set.of(new UUID(0L, 0L)),
            true,
            processingOrgUnitIds,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            callerId,
            ALL_ROWS)
        .stream()
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private Set<UUID> visibleIds(ScopePredicate scope) {
    return visibleIds(scope, NIL);
  }

  private Set<UUID> visibleIds(ScopePredicate scope, UUID callerId) {
    return jobOrderRepository
        .findScopedJobOrders(
            ALL_STATUSES,
            true,
            Set.of(new UUID(0L, 0L)),
            false,
            Set.of(new UUID(0L, 0L)),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            callerId,
            ALL_ROWS)
        .stream()
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private Squadron newSquadron(String name, String shorthand) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    s.setProfitEligible(true);
    return squadronRepository.save(s);
  }

  private SpecialCommand newSpecialCommand(String name, String shorthand) {
    SpecialCommand sc = new SpecialCommand();
    sc.setName(name);
    sc.setShorthand(shorthand);
    sc.setProfitEligible(true);
    return specialCommandRepository.save(sc);
  }

  private JobOrder newOrder(OrgUnit responsible, OrgUnit requesting) {
    JobOrder o =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .handle("scope-test")
            .status(JobOrderStatus.OPEN)
            .build();
    return jobOrderRepository.save(o);
  }
}
