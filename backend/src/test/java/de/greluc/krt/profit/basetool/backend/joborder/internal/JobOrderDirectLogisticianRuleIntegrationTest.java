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
import static org.assertj.core.api.Assertions.tuple;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateUnitOptionDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreationOptions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The exchange's writer rule at the create seam, against Postgres: a direct member who is a
 * Logistician of a unit by the org-stock rule writes for it as the requesting side, decided from
 * the database (REQ-XCH-009, ADR-0405).
 */
@SpringBootTest
@Transactional
class JobOrderDirectLogisticianRuleIntegrationTest {

  @Autowired private JobOrderCreationOptions creationOptions;
  @Autowired private JobOrderCreateCommand createCommand;
  @Autowired private JobOrderRepository orders;
  @Autowired private JobOrderMoneyTargetRepository targets;
  @Autowired private JobOrderWriters writers;
  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private ProfitHierarchyService profitHierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private OrganisationsleitungRepository leitungen;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private OrgUnitMembershipRepository memberships;

  private OrgUnit profit;
  private Squadron profitStaffel;
  private Squadron staffel;
  private Bereich area;
  private SpecialCommand areaCommand;
  private Squadron outside;
  private Set<UUID> seeded;

  @BeforeEach
  void seed() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    profitStaffel = squadron("Rule profit " + tag, profit, true);
    staffel = squadron("Rule staffel " + tag, null, true);
    area = new Bereich();
    area.setName("Rule area " + tag);
    area.setShorthand("RA" + tag);
    area = units.save(area);
    areaCommand = new SpecialCommand();
    areaCommand.setName("Rule area command " + tag);
    areaCommand.setShorthand("RC" + tag);
    areaCommand.setParent(area);
    areaCommand = units.save(areaCommand);
    outside = squadron("Rule outside " + tag, null, true);
    seeded =
        Set.of(
            profit.getId(),
            profitStaffel.getId(),
            staffel.getId(),
            area.getId(),
            areaCommand.getId(),
            outside.getId());
  }

  @Test
  void aLogisticianFlagOnADirectUnitMakesItARequestingUnit() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.MEMBER, true);
    seat(member, outside, MembershipRole.MEMBER, false);

    assertThat(requestingUnits(member)).containsExactly(staffel.getId());
  }

  @Test
  void aLeadershipRankOnADirectUnitMakesItARequestingUnit() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.STAFFELLEITER, false);

    assertThat(requestingUnits(member)).containsExactly(staffel.getId());
  }

  @Test
  void plainMembershipMakesNoRequestingUnit() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.MEMBER, false);

    assertThat(requestingUnits(member)).isEmpty();
    assertThat(optionsOf(member).canCreate()).isFalse();
  }

  @Test
  void bereichReachCountsOnlyOverAUnitTheMemberDirectlyBelongsTo() {
    User withMembership = member(true, false);
    seat(withMembership, area, MembershipRole.BEREICHSLEITER, false);
    seat(withMembership, areaCommand, MembershipRole.MEMBER, false);
    User reachOnly = member(true, false);
    seat(reachOnly, area, MembershipRole.BEREICHSKOORDINATOR, false);

    assertThat(requestingUnits(withMembership))
        .containsExactlyInAnyOrder(area.getId(), areaCommand.getId());
    assertThat(requestingUnits(reachOnly)).containsExactly(area.getId());
  }

  @Test
  void organisationsleitungReachCountsOnlyOverAUnitTheMemberDirectlyBelongsTo() {
    Organisationsleitung leitung = leitung();
    User withMembership = member(true, false);
    seat(withMembership, leitung, MembershipRole.OL_MEMBER, false);
    seat(withMembership, areaCommand, MembershipRole.MEMBER, false);
    User reachOnly = member(true, false);
    seat(reachOnly, leitung, MembershipRole.OL_MEMBER, false);

    assertThat(requestingUnits(withMembership)).containsExactly(areaCommand.getId());
    assertThat(requestingUnits(reachOnly)).isEmpty();
  }

  @Test
  void aDeactivatedUnitConfersNothing() {
    User flagged = member(true, false);
    seat(flagged, staffel, MembershipRole.STAFFELLEITER, true);
    seat(flagged, outside, MembershipRole.MEMBER, true);
    User areaLead = member(true, false);
    seat(areaLead, area, MembershipRole.BEREICHSLEITER, false);
    seat(areaLead, areaCommand, MembershipRole.MEMBER, false);
    staffel.setActive(false);
    units.saveAndFlush(staffel);
    areaCommand.setActive(false);
    units.saveAndFlush(areaCommand);

    assertThat(requestingUnits(flagged)).containsExactly(outside.getId());
    assertThat(requestingUnits(areaLead)).containsExactly(area.getId());
  }

  @Test
  void withoutKrtMemberNoSeatOrFlagConfersAnything() {
    User flagged = member(false, false);
    seat(flagged, staffel, MembershipRole.STAFFELLEITER, true);
    User areaLead = member(false, false);
    seat(areaLead, area, MembershipRole.BEREICHSLEITER, false);
    seat(areaLead, areaCommand, MembershipRole.MEMBER, true);

    assertThat(requestingUnits(flagged)).isEmpty();
    assertThat(requestingUnits(areaLead)).isEmpty();
    assertThat(optionsOf(flagged).canCreate()).isFalse();
  }

  @Test
  void anAdministratorIsAPlainMember() {
    User admin = member(true, true);
    seat(admin, staffel, MembershipRole.MEMBER, false);
    seat(admin, outside, MembershipRole.MEMBER, true);
    authenticateAsAdministrator(admin);

    assertThat(requestingUnits(admin)).containsExactly(outside.getId());
  }

  @Test
  void theResponsibleSideNeverWidensTheRequestingUnits() {
    User member = member(true, false);
    seat(member, profitStaffel, MembershipRole.MEMBER, true);

    JobOrderCreateOptionsDto options = optionsOf(member);

    assertThat(options.canCreate()).isTrue();
    assertThat(seededIds(options.requestingOptions())).containsExactly(profitStaffel.getId());
    assertThat(options.responsibleOptions())
        .filteredOn(option -> seeded.contains(option.orgUnitId()))
        .extracting(JobOrderCreateUnitOptionDto::orgUnitId, JobOrderCreateUnitOptionDto::canWrite)
        .contains(tuple(profit.getId(), false), tuple(profitStaffel.getId(), false));
    assertThat(options.suggestedAuecResponsibleOrgUnitId()).isEqualTo(profit.getId());
  }

  @Test
  void aRequestingWriterCreatesAnAuecOrderForTheNamedPair() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.MEMBER, true);

    UUID id = createAuec(member, profitStaffel, staffel);

    JobOrder order = orders.findById(id).orElseThrow();
    assertThat(order.getType()).isEqualTo(JobOrderType.AUEC);
    assertThat(order.getStatus()).isEqualTo(JobOrderStatus.OPEN);
    assertThat(order.getResponsibleOrgUnit().getId()).isEqualTo(profitStaffel.getId());
    assertThat(order.getRequestingOrgUnit().getId()).isEqualTo(staffel.getId());
    assertThat(order.getHandle()).isEqualTo("Rule contact");
    assertThat(targets.findById(id).orElseThrow().getAmount()).isEqualByComparingTo("250000");
  }

  @Test
  void aWriterOfTheResponsibleSideAloneCannotCreate() {
    User member = member(true, false);
    seat(member, profitStaffel, MembershipRole.MEMBER, true);
    seat(member, outside, MembershipRole.MEMBER, false);

    assertThatThrownBy(() -> createAuec(member, profitStaffel, outside))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aRequestingUnitReachedWithoutDirectMembershipCannotBeNamed() {
    User member = member(true, false);
    seat(member, area, MembershipRole.BEREICHSLEITER, false);

    assertThatThrownBy(() -> createAuec(member, profitStaffel, areaCommand))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aDeactivatedRequestingUnitCannotBeNamed() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.MEMBER, true);
    staffel.setActive(false);
    units.saveAndFlush(staffel);

    assertThatThrownBy(() -> createAuec(member, profitStaffel, staffel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aResponsibleUnitOutsideTheProfitHierarchyCannotBeNamed() {
    User member = member(true, false);
    seat(member, staffel, MembershipRole.MEMBER, true);

    assertThatThrownBy(() -> createAuec(member, outside, staffel))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void anAdministratorWithoutAWriterUnitCannotCreate() {
    User admin = member(true, true);
    seat(admin, staffel, MembershipRole.MEMBER, false);
    authenticateAsAdministrator(admin);

    assertThatThrownBy(() -> createAuec(admin, profitStaffel, staffel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void everyPairTheExchangeRulePermitsIsOneTheWebPermitsForTheSameMemberWithoutAPin() {
    User flagged = member(true, false);
    seat(flagged, staffel, MembershipRole.MEMBER, true);
    seat(flagged, profitStaffel, MembershipRole.MEMBER, true);
    User squadronLead = member(true, false);
    seat(squadronLead, outside, MembershipRole.STAFFELLEITER, false);
    seat(squadronLead, staffel, MembershipRole.MEMBER, false);
    User areaLead = member(true, false);
    seat(areaLead, area, MembershipRole.BEREICHSLEITER, false);
    seat(areaLead, areaCommand, MembershipRole.MEMBER, false);
    User leitungMember = member(true, false);
    seat(leitungMember, leitung(), MembershipRole.OL_MEMBER, false);
    seat(leitungMember, areaCommand, MembershipRole.MEMBER, true);
    User admin = member(true, true);
    seat(admin, staffel, MembershipRole.MEMBER, true);

    for (User member : List.of(flagged, squadronLead, areaLead, leitungMember, admin)) {
      Set<List<UUID>> exchange = permittedPairs(JobOrderWriterRule.DIRECT_LOGISTICIAN, member);
      Set<List<UUID>> web = permittedPairs(JobOrderWriterRule.CONTEXTUAL, member);

      assertThat(exchange).as("exchange pairs of %s", member.getUsername()).isNotEmpty();
      assertThat(web).as("web pairs of %s", member.getUsername()).containsAll(exchange);
    }
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  /**
   * Lists the pairs of seeded units the rule lets the member commission, with the web's authorities
   * minted for the member by the real converter and no pin.
   */
  private Set<List<UUID>> permittedPairs(JobOrderWriterRule rule, User member) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("pair-subset")
                    .header("alg", "none")
                    .subject(member.getId().toString())
                    .build(),
                converter.assembleFor(users.findById(member.getId()).orElseThrow())));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    JobOrderWriters.Scope scope =
        writers.resolve(
            rule,
            rule == JobOrderWriterRule.CONTEXTUAL
                ? new JobOrderActor.Web(member.getId())
                : new JobOrderActor.Client(member.getId(), "rule-test-client"));
    Set<List<UUID>> pairs = new HashSet<>();
    for (UUID responsible : seeded) {
      for (UUID requesting : seeded) {
        if (profitHierarchy.isEligible(responsible) && scope.permitsPair(responsible, requesting)) {
          pairs.add(List.of(responsible, requesting));
        }
      }
    }
    return pairs;
  }

  private UUID createAuec(User member, OrgUnit responsible, OrgUnit requesting) {
    return createCommand.createAuec(
        new JobOrderCreateCommand.AuecOrder(
            responsible.getId(),
            requesting.getId(),
            "Rule contact",
            null,
            new BigDecimal("250000")),
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        new JobOrderActor.Client(member.getId(), "rule-test-client"));
  }

  private static void authenticateAsAdministrator(User admin) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(
                admin.getId().toString(),
                null,
                List.of(
                    new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)),
                    new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)))));
  }

  private Set<UUID> requestingUnits(User member) {
    return seededIds(optionsOf(member).requestingOptions());
  }

  private JobOrderCreateOptionsDto optionsOf(User member) {
    return creationOptions.current(
        JobOrderWriterRule.DIRECT_LOGISTICIAN,
        new JobOrderActor.Client(member.getId(), "rule-test-client"));
  }

  private Set<UUID> seededIds(List<JobOrderCreateUnitOptionDto> options) {
    return options.stream()
        .filter(JobOrderCreateUnitOptionDto::canWrite)
        .map(JobOrderCreateUnitOptionDto::orgUnitId)
        .filter(seeded::contains)
        .collect(Collectors.toSet());
  }

  private Organisationsleitung leitung() {
    return leitungen.findAll().stream()
        .findFirst()
        .orElseGet(() -> {
          Organisationsleitung created = new Organisationsleitung();
          created.setName("Rule OL " + area.getShorthand());
          created.setShorthand("O" + area.getShorthand());
          return leitungen.save(created);
        });
  }

  private Squadron squadron(String name, @Nullable OrgUnit parent, boolean active) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    unit.setActive(active);
    return units.save(unit);
  }

  private User member(boolean krtMember, boolean admin) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rule-" + user.getId());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<de.greluc.krt.profit.basetool.backend.identity.api.Role> held = new HashSet<>();
    if (krtMember) {
      held.add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
    }
    if (admin) {
      held.add(roles.findByCode(Roles.ADMIN).orElseThrow());
    }
    user.setRoles(held);
    return users.saveAndFlush(user);
  }

  private void seat(User user, OrgUnit unit, MembershipRole rank, boolean logistician) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(unit.getKind());
    membership.setRole(rank);
    membership.setLogistician(logistician);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }
}
