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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRelation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The member's job-order read set on the exchange against Postgres: each current own relation
 * counts, every other reach of the web does not, and the set never exceeds the web's read for the
 * same member (REQ-OXCH-001).
 */
@SpringBootTest
@Transactional
class JobOrderExchangeReadSetIntegrationTest {

  @Autowired private JobOrderExchangeReadSet readSet;
  @Autowired private JobOrderAccessPolicy accessPolicy;
  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private JobOrderRepository orders;
  @Autowired private OrgUnitRepository units;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private OrgUnit profit;
  private Squadron own;
  private Squadron other;
  private Squadron third;

  @BeforeEach
  void seed() {
    profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    own = squadron("Read own", profit, true);
    other = squadron("Read other", profit, true);
    third = squadron("Read third", null, true);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void eachCurrentOwnRelationPutsTheOrderIntoTheReadSet() {
    User member = member(true, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder requested = order(other, own, JobOrderType.MATERIAL);
    JobOrder responsible = order(own, other, JobOrderType.ITEM);
    JobOrder supported = order(other, third, JobOrderType.MATERIAL);
    participation(supported, own, true);
    JobOrder assigned = order(other, third, JobOrderType.AUEC);
    assignee(assigned, member, "own note");

    assertThat(relationsOf(member, requested, responsible, supported, assigned))
        .containsOnly(
            Map.entry(requested.getId(), Set.of(JobOrderRelation.REQUESTING)),
            Map.entry(responsible.getId(), Set.of(JobOrderRelation.RESPONSIBLE)),
            Map.entry(supported.getId(), Set.of(JobOrderRelation.SUPPORTING)),
            Map.entry(assigned.getId(), Set.of(JobOrderRelation.ASSIGNEE)));
  }

  @Test
  void oneOrderCarriesEveryRelationThatApplies() {
    User member = member(true, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder order = order(own, own, JobOrderType.MATERIAL);
    participation(order, own, true);
    assignee(order, member, null);

    assertThat(relationsOf(member, order).get(order.getId()))
        .containsExactlyInAnyOrder(JobOrderRelation.values());
  }

  @Test
  void theLeadershipCascadeStaysOutside() {
    User leader = member(true, false);
    Bereich area = bereich("Read area");
    seat(leader, area, MembershipRole.BEREICHSLEITER);
    Squadron below = squadron("Read below", area, true);
    JobOrder ofTheArea = order(other, area, JobOrderType.MATERIAL);
    JobOrder cascaded = order(other, below, JobOrderType.MATERIAL);

    assertThat(relationsOf(leader, ofTheArea, cascaded)).containsOnlyKeys(ofTheArea.getId());
  }

  @Test
  void theWebsWiderReachesStayOutside() {
    User member = member(true, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder profitWide = order(other, other, JobOrderType.MATERIAL);
    JobOrder endedSupport = order(other, third, JobOrderType.MATERIAL);
    participation(endedSupport, own, false);
    JobOrder historical = order(other, third, JobOrderType.ITEM);
    jdbc.update(
        "INSERT INTO job_order_unit_read_history (job_order_id, org_unit_id) VALUES (?, ?)",
        historical.getId(),
        own.getId());
    JobOrder holderLink = order(other, third, JobOrderType.AUEC);
    jdbc.update(
        "INSERT INTO job_order_money_holder (id, order_id, user_id, revision) VALUES (?, ?, ?, 1)",
        UUID.randomUUID(),
        holderLink.getId(),
        member.getId());

    assertThat(relationsOf(member, profitWide, endedSupport, historical, holderLink)).isEmpty();
  }

  @Test
  void aDeactivatedUnitRelatesNothing() {
    User member = member(true, false);
    Squadron dormant = squadron("Read dormant", null, false);
    seat(member, dormant, MembershipRole.MEMBER);
    JobOrder order = order(other, dormant, JobOrderType.MATERIAL);

    assertThat(relationsOf(member, order)).isEmpty();
  }

  @Test
  void withoutKrtMemberOnlyTheAssigneeEntryCounts() {
    User member = member(false, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder requested = order(other, own, JobOrderType.MATERIAL);
    JobOrder assigned = order(other, third, JobOrderType.MATERIAL);
    assignee(assigned, member, null);

    assertThat(relationsOf(member, requested, assigned))
        .containsOnly(Map.entry(assigned.getId(), Set.of(JobOrderRelation.ASSIGNEE)));
  }

  @Test
  void anAdministratorIsAPlainMember() {
    User admin = member(true, true);
    seat(admin, own, MembershipRole.MEMBER);
    JobOrder mine = order(other, own, JobOrderType.MATERIAL);
    JobOrder foreign = order(other, third, JobOrderType.MATERIAL);

    assertThat(relationsOf(admin, mine, foreign)).containsOnlyKeys(mine.getId());
  }

  @Test
  void anUnknownOrderIsOutsideLikeAHiddenOne() {
    User member = member(true, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder hidden = order(other, third, JobOrderType.MATERIAL);

    assertThat(
            readSet.relations(
                readSet.relatingMember(member.getId()), List.of(UUID.randomUUID(), hidden.getId())))
        .isEmpty();
  }

  @Test
  void theOwnRelationReadCheckDecidesTheReadSetOfTheRequestsMember() {
    User member = member(true, false);
    seat(member, own, MembershipRole.MEMBER);
    JobOrder requested = order(other, own, JobOrderType.AUEC);
    JobOrder foreign = order(other, third, JobOrderType.AUEC);

    assertThat(accessPolicy.canRead(JobOrderReadCheck.OWN_RELATION, requested))
        .as("a request without a member")
        .isFalse();
    authenticateForTheWeb(member);
    assertThat(accessPolicy.canRead(JobOrderReadCheck.OWN_RELATION, requested)).isTrue();
    assertThat(accessPolicy.canRead(JobOrderReadCheck.OWN_RELATION, foreign)).isFalse();
  }

  @Test
  void onlyTheMembersOwnNoteIsRead() {
    User member = member(true, false);
    User colleague = member(true, false);
    JobOrder order = order(other, third, JobOrderType.MATERIAL);
    assignee(order, member, "mine");
    assignee(order, colleague, "theirs");
    JobOrder silent = order(other, third, JobOrderType.MATERIAL);
    assignee(silent, member, null);

    assertThat(readSet.ownNotes(member.getId(), List.of(order.getId(), silent.getId())))
        .containsExactly(Map.entry(order.getId(), "mine"));
  }

  @Test
  void theReadSetNeverExceedsTheWebsReadForTheSameMember() {
    User outsideProfit = member(true, false);
    seat(outsideProfit, third, MembershipRole.MEMBER);
    User insideProfit = member(true, false);
    seat(insideProfit, own, MembershipRole.MEMBER);
    User admin = member(true, true);
    seat(admin, own, MembershipRole.MEMBER);
    JobOrder requested = order(other, third, JobOrderType.MATERIAL);
    JobOrder responsible = order(own, other, JobOrderType.ITEM);
    JobOrder supported = order(other, other, JobOrderType.MATERIAL);
    participation(supported, third, true);
    JobOrder assigned = order(other, other, JobOrderType.AUEC);
    assignee(assigned, outsideProfit, null);
    List<JobOrder> seeded = List.of(requested, responsible, supported, assigned);
    entityManager.flush();
    entityManager.clear();

    for (User reader : List.of(outsideProfit, insideProfit, admin)) {
      Set<UUID> exchange = relationsOf(reader, seeded.toArray(JobOrder[]::new)).keySet();
      assertThat(exchange).as("exchange read set of %s", reader.getUsername()).isNotEmpty();
      authenticateForTheWeb(reader);
      for (UUID order : exchange) {
        assertThat(accessPolicy.canSeeJobOrder(order))
            .as("%s reads %s in the web", reader.getUsername(), order)
            .isTrue();
      }
      clearAuthentication();
    }
  }

  private Map<UUID, Set<JobOrderRelation>> relationsOf(User member, JobOrder... seeded) {
    return readSet.relations(
        readSet.relatingMember(member.getId()),
        List.of(seeded).stream().map(JobOrder::getId).toList());
  }

  /** Authenticates the member with the web's authorities minted by the real converter, no pin. */
  private void authenticateForTheWeb(User member) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("read-subset")
                    .header("alg", "none")
                    .subject(member.getId().toString())
                    .build(),
                converter.assembleFor(users.findById(member.getId()).orElseThrow())));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private JobOrder order(OrgUnit responsible, OrgUnit requesting, JobOrderType kind) {
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(responsible)
                .requestingOrgUnit(requesting)
                .type(kind)
                .status(JobOrderStatus.OPEN)
                .handle("read-set contact")
                .build());
    if (kind == JobOrderType.AUEC) {
      jdbc.update(
          "INSERT INTO job_order_money_target (order_id, amount, version) VALUES (?, 100, 0)",
          order.getId());
    }
    return order;
  }

  private void participation(JobOrder order, OrgUnit unit, boolean active) {
    jdbc.update(
        "INSERT INTO job_order_participation (id, job_order_id, claiming_org_unit_id, active)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        order.getId(),
        unit.getId(),
        active);
  }

  private void assignee(JobOrder order, User member, @Nullable String note) {
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note, version)"
            + " VALUES (?, ?, ?, ?, 0)",
        UUID.randomUUID(),
        order.getId(),
        member.getId(),
        note);
  }

  private Squadron squadron(String name, @Nullable OrgUnit parent, boolean active) {
    String tag = name + " " + UUID.randomUUID().toString().substring(0, 8);
    Squadron unit = new Squadron();
    unit.setName(tag);
    unit.setShorthand(tag);
    unit.setParent(parent);
    unit.setActive(active);
    return units.save(unit);
  }

  private Bereich bereich(String name) {
    String tag = name + " " + UUID.randomUUID().toString().substring(0, 8);
    Bereich unit = new Bereich();
    unit.setName(tag);
    unit.setShorthand(tag);
    return units.save(unit);
  }

  private User member(boolean krtMember, boolean admin) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("read-set-" + user.getId());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<Role> held = new HashSet<>();
    if (krtMember) {
      held.add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
    }
    if (admin) {
      held.add(roles.findByCode(Roles.ADMIN).orElseThrow());
    }
    user.setRoles(held);
    return users.saveAndFlush(user);
  }

  private void seat(User user, OrgUnit unit, MembershipRole rank) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(unit.getKind());
    membership.setRole(rank);
    membership.setLogistician(false);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }
}
