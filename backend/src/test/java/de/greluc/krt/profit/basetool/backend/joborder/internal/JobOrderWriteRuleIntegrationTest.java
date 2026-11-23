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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatusCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderTargetCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exchange's writer rule at the status, target and information commands, against Postgres: a
 * direct Logistician of the requesting unit cancels, changes the target and changes the comment
 * alone; a writer of the responsible side, a reach without direct membership and an administrator
 * write nothing through it, and every write it permits is one the web permits for the same member
 * (REQ-XCH-009, ADR-0408).
 */
@SpringBootTest
@Transactional
class JobOrderWriteRuleIntegrationTest {

  private static final String CLIENT = "write-rule-client";

  @Autowired private JobOrderStatusCommand statusCommand;
  @Autowired private JobOrderTargetCommand targetCommand;
  @Autowired private JobOrderInformationCommand informationCommand;
  @Autowired private JobOrderCreateCommand createCommand;
  @Autowired private JobOrderRepository orders;
  @Autowired private JobOrderMoneyTargetRepository targets;
  @Autowired private JobOrderMoneyTargetChangeRepository targetHistory;
  @Autowired private JobOrderWriters writers;
  @Autowired private ProfitHierarchyService profitHierarchy;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private OrganisationsleitungRepository leitungen;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;

  private WriterRuleFixtures fixtures;
  private WriterRuleFixtures.Units seeded;
  private Squadron profitStaffel;
  private Squadron staffel;
  private Bereich area;
  private SpecialCommand areaCommand;
  private Squadron outside;

  @BeforeEach
  void seed() {
    fixtures = new WriterRuleFixtures(units, leitungen, users, roles, memberships, converter);
    seeded = fixtures.seedUnits();
    profitStaffel = seeded.profitStaffel();
    staffel = seeded.staffel();
    area = seeded.area();
    areaCommand = seeded.areaCommand();
    outside = seeded.outside();
  }

  @AfterEach
  void clearAuthentication() {
    WriterRuleFixtures.signOut();
  }

  @Test
  void aDirectLogisticianOfTheRequestingUnitCancelsAnActiveOrder() {
    User member = requestingLogistician();
    UUID order = createAuec(member, staffel);

    WriterRuleFixtures.signInAsExchangeMember(member);
    statusCommand.cancel(order, 0L, rule(), read(), client(member));

    JobOrder cancelled = orders.findById(order).orElseThrow();
    assertThat(cancelled.getStatus()).isEqualTo(JobOrderStatus.CANCELLED);
    assertThat(cancelled.getLifecycleVersion()).isEqualTo(1L);
  }

  @Test
  void aDirectLogisticianOfTheRequestingUnitChangesTheTarget() {
    User member = requestingLogistician();
    UUID order = createAuec(member, staffel);

    WriterRuleFixtures.signInAsExchangeMember(member);
    targetCommand.changeTarget(order, new BigDecimal("300000"), 0L, rule(), read(), client(member));

    assertThat(targets.findById(order).orElseThrow().getAmount()).isEqualByComparingTo("300000");
    assertThat(targetHistory.findAll())
        .filteredOn(change -> change.getOrderId().equals(order))
        .singleElement()
        .satisfies(change -> assertThat(change.getActorId()).isEqualTo(member.getId()));
  }

  @Test
  void theCommentChangesAloneUnderItsOwnVersionWhateverTheContactVersion() {
    User member = requestingLogistician();
    UUID order = createAuec(member, staffel);
    JobOrder contactChanged = orders.findById(order).orElseThrow();
    contactChanged.setMoneyInformationVersion(3L);
    orders.saveAndFlush(contactChanged);

    WriterRuleFixtures.signInAsExchangeMember(member);
    informationCommand.changeComment(
        order, " Deliver to Area18 ", 0L, rule(), read(), client(member));

    JobOrder written = orders.findById(order).orElseThrow();
    assertThat(written.getComment()).isEqualTo("Deliver to Area18");
    assertThat(written.getCommentVersion()).isEqualTo(1L);
    assertThat(written.getHandle()).isEqualTo("Rule contact");
    assertThat(written.getMoneyInformationVersion()).isEqualTo(3L);
    assertThatThrownBy(() ->
            informationCommand.changeComment(
                order, "Late note", 0L, rule(), read(), client(member)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void aWriterOfTheResponsibleSideAloneWritesNothingThroughTheRule() {
    UUID order = createAuec(requestingLogistician(), staffel);
    User responsibleWriter = fixtures.member(true, false);
    fixtures.seat(responsibleWriter, profitStaffel, MembershipRole.STAFFELLEITER, true);

    WriterRuleFixtures.signInAsExchangeMember(responsibleWriter);

    assertRefusedEveryWrite(order, responsibleWriter, AccessDeniedException.class);
  }

  @Test
  void aRequestingUnitReachedWithoutDirectMembershipWritesNothing() {
    User commandLogistician = fixtures.member(true, false);
    fixtures.seat(commandLogistician, areaCommand, MembershipRole.MEMBER, true);
    UUID order = createAuec(commandLogistician, areaCommand);
    User areaLead = fixtures.member(true, false);
    fixtures.seat(areaLead, area, MembershipRole.BEREICHSLEITER, false);

    WriterRuleFixtures.signInAsExchangeMember(areaLead);

    assertRefusedEveryWrite(order, areaLead, AccessDeniedException.class);
  }

  @Test
  void anAdministratorIsAPlainMember() {
    UUID order = createAuec(requestingLogistician(), staffel);
    User admin = fixtures.member(true, true);
    fixtures.seat(admin, staffel, MembershipRole.MEMBER, false);

    WriterRuleFixtures.signInAsAdministrator(admin);

    assertRefusedEveryWrite(order, admin, AccessDeniedException.class);
  }

  @Test
  void invalidResponsibilityRefusesEveryWriteOfTheRuleToo() {
    User member = requestingLogistician();
    UUID order = createAuec(member, staffel);
    hierarchy.setParent(profitStaffel.getId(), null, profitStaffel.getVersion());

    WriterRuleFixtures.signInAsExchangeMember(member);

    assertRefusedEveryWrite(order, member, JobOrderClientWriteRefusedException.class);
  }

  @Test
  void everyWriteTheExchangeRulePermitsIsOneTheWebPermitsForTheSameMemberWithoutAPin() {
    User flagged = fixtures.member(true, false);
    fixtures.seat(flagged, staffel, MembershipRole.MEMBER, true);
    fixtures.seat(flagged, profitStaffel, MembershipRole.MEMBER, true);
    User squadronLead = fixtures.member(true, false);
    fixtures.seat(squadronLead, outside, MembershipRole.STAFFELLEITER, false);
    fixtures.seat(squadronLead, staffel, MembershipRole.MEMBER, false);
    User areaLead = fixtures.member(true, false);
    fixtures.seat(areaLead, area, MembershipRole.BEREICHSLEITER, false);
    fixtures.seat(areaLead, areaCommand, MembershipRole.MEMBER, false);
    User leitungMember = fixtures.member(true, false);
    fixtures.seat(leitungMember, fixtures.leitung(), MembershipRole.OL_MEMBER, false);
    fixtures.seat(leitungMember, areaCommand, MembershipRole.MEMBER, true);
    User admin = fixtures.member(true, true);
    fixtures.seat(admin, staffel, MembershipRole.MEMBER, true);

    for (User member : List.of(flagged, squadronLead, areaLead, leitungMember, admin)) {
      Set<List<Object>> exchange = permittedWrites(JobOrderWriterRule.DIRECT_LOGISTICIAN, member);
      Set<List<Object>> web = permittedWrites(JobOrderWriterRule.CONTEXTUAL, member);

      assertThat(exchange).as("exchange writes of %s", member.getUsername()).isNotEmpty();
      assertThat(web).as("web writes of %s", member.getUsername()).containsAll(exchange);
    }
  }

  /**
   * Lists every write the rule lets the member make on an order of each seeded pair in each status:
   * each status change, and the target and comment changes, with the web's authorities minted for
   * the member by the real converter and no pin.
   */
  private Set<List<Object>> permittedWrites(JobOrderWriterRule rule, User member) {
    fixtures.signInAsWebMember(member);
    JobOrderWriters.Scope scope =
        writers.resolve(
            rule,
            rule == JobOrderWriterRule.CONTEXTUAL
                ? new JobOrderActor.Web(member.getId())
                : client(member));
    Set<List<Object>> writes = new HashSet<>();
    for (OrgUnit responsible : seeded.all()) {
      if (!profitHierarchy.isEligible(responsible.getId())) {
        continue;
      }
      for (OrgUnit requesting : seeded.all()) {
        for (JobOrderStatus current : JobOrderStatus.values()) {
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(responsible)
                  .requestingOrgUnit(requesting)
                  .build();
          order.setStatus(current);
          List<Object> on = List.of(responsible.getId(), requesting.getId(), current);
          for (JobOrderStatus next : JobOrderStatus.values()) {
            if (scope.permitsStatus(order, next)) {
              writes.add(concat(on, next));
            }
          }
          if (scope.writesEitherSide(order)) {
            writes.add(concat(on, "target and information"));
          }
        }
      }
    }
    return writes;
  }

  private static List<Object> concat(List<Object> order, Object write) {
    List<Object> key = new ArrayList<>(order);
    key.add(write);
    return key;
  }

  /**
   * Asserts that the cancel and the target and comment writes are refused and change nothing; all
   * three answer in the order of the client writes' shared rules (REQ-OXCH-005).
   */
  private void assertRefusedEveryWrite(
      UUID order, User member, Class<? extends RuntimeException> refusal) {
    assertThatThrownBy(() -> statusCommand.cancel(order, 0L, rule(), read(), client(member)))
        .isInstanceOf(refusal);
    assertThatThrownBy(() ->
            targetCommand.changeTarget(
                order, new BigDecimal("300000"), 0L, rule(), read(), client(member)))
        .isInstanceOf(refusal);
    assertThatThrownBy(() ->
            informationCommand.changeComment(order, "Note", 0L, rule(), read(), client(member)))
        .isInstanceOf(refusal);
    JobOrder unchanged = orders.findById(order).orElseThrow();
    assertThat(unchanged.getStatus()).isEqualTo(JobOrderStatus.OPEN);
    assertThat(unchanged.getComment()).isNull();
    assertThat(targets.findById(order).orElseThrow().getAmount()).isEqualByComparingTo("250000");
  }

  private User requestingLogistician() {
    User member = fixtures.member(true, false);
    fixtures.seat(member, staffel, MembershipRole.MEMBER, true);
    return member;
  }

  private UUID createAuec(User creator, OrgUnit requesting) {
    return createCommand.createAuec(
        new JobOrderCreateCommand.AuecOrder(
            profitStaffel.getId(),
            requesting.getId(),
            "Rule contact",
            null,
            new BigDecimal("250000")),
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        client(creator),
        "rule-key-" + UUID.randomUUID());
  }

  private static JobOrderWriterRule rule() {
    return JobOrderWriterRule.DIRECT_LOGISTICIAN;
  }

  private static JobOrderReadCheck read() {
    return JobOrderReadCheck.CONTEXTUAL;
  }

  private static JobOrderActor.Client client(User member) {
    return new JobOrderActor.Client(member.getId(), CLIENT);
  }
}
