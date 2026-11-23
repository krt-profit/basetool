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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
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
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Seeds the units, members and memberships the job-order writer-rule tests decide over, and signs a
 * member in the way the web and the exchange present one (ADR-0408).
 */
@RequiredArgsConstructor
final class WriterRuleFixtures {

  private final OrgUnitRepository units;
  private final OrganisationsleitungRepository leitungen;
  private final UserRepository users;
  private final RoleRepository roles;
  private final OrgUnitMembershipRepository memberships;
  private final CustomJwtGrantedAuthoritiesConverter converter;

  /**
   * The seeded units: the Profit area with an eligible Staffel below it, a Staffel and a Bereich
   * with its Spezialkommando outside the Profit hierarchy, and an unrelated Staffel.
   *
   * @param profit the Profit area
   * @param profitStaffel the eligible Staffel below the Profit area
   * @param staffel a Staffel outside the Profit hierarchy
   * @param area a Bereich outside the Profit hierarchy
   * @param areaCommand the Spezialkommando below {@code area}
   * @param outside an unrelated Staffel
   */
  record Units(
      @NotNull OrgUnit profit,
      @NotNull Squadron profitStaffel,
      @NotNull Squadron staffel,
      @NotNull Bereich area,
      @NotNull SpecialCommand areaCommand,
      @NotNull Squadron outside) {

    /**
     * Lists every seeded unit.
     *
     * @return the six units
     */
    @NotNull
    List<OrgUnit> all() {
      return List.of(profit, profitStaffel, staffel, area, areaCommand, outside);
    }
  }

  /**
   * Persists a fresh set of {@link Units} with unique names.
   *
   * @return the seeded units
   */
  @NotNull
  Units seedUnits() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    Bereich area = new Bereich();
    area.setName("Rule area " + tag);
    area.setShorthand("RA" + tag);
    area = units.save(area);
    SpecialCommand areaCommand = new SpecialCommand();
    areaCommand.setName("Rule area command " + tag);
    areaCommand.setShorthand("RC" + tag);
    areaCommand.setParent(area);
    areaCommand = units.save(areaCommand);
    return new Units(
        profit,
        squadron("Rule profit " + tag, profit),
        squadron("Rule staffel " + tag, null),
        area,
        areaCommand,
        squadron("Rule outside " + tag, null));
  }

  /**
   * Persists an active member.
   *
   * @param krtMember whether the member holds {@code KRT Member}
   * @param admin whether the member holds the administrator role
   * @return the persisted member
   */
  @NotNull
  User member(boolean krtMember, boolean admin) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rule-" + user.getId());
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

  /**
   * Seats the member in the unit.
   *
   * @param user the member
   * @param unit the unit
   * @param rank the membership rank
   * @param logistician the membership's Logistician flag
   */
  void seat(
      @NotNull User user,
      @NotNull OrgUnit unit,
      @NotNull MembershipRole rank,
      boolean logistician) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(unit.getKind());
    membership.setRole(rank);
    membership.setLogistician(logistician);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }

  /**
   * Returns the Organisationsleitung, created when none exists.
   *
   * @return the Organisationsleitung
   */
  @NotNull
  Organisationsleitung leitung() {
    return leitungen.findAll().stream()
        .findFirst()
        .orElseGet(() -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          Organisationsleitung created = new Organisationsleitung();
          created.setName("Rule OL " + tag);
          created.setShorthand("O" + tag);
          return leitungen.save(created);
        });
  }

  /**
   * Signs the member in as the web does: the authorities the real converter mints for them, no pin.
   *
   * @param member the member
   */
  void signInAsWebMember(@NotNull User member) {
    signIn(member, converter.assembleFor(users.findById(member.getId()).orElseThrow()));
  }

  /**
   * Signs the member in as the exchange's acting member: {@code KRT Member} and no contextual
   * authority.
   *
   * @param member the member
   */
  static void signInAsExchangeMember(@NotNull User member) {
    signIn(member, List.of(new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER))));
  }

  /**
   * Signs the member in as an administrator without a pin.
   *
   * @param admin the member
   */
  static void signInAsAdministrator(@NotNull User admin) {
    signIn(
        admin,
        List.of(
            new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)),
            new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER))));
  }

  /** Clears the signed-in member and the request. */
  static void signOut() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static void signIn(
      @NotNull User member, @NotNull Collection<? extends GrantedAuthority> authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("writer-rule")
                    .header("alg", "none")
                    .subject(member.getId().toString())
                    .build(),
                authorities));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private Squadron squadron(@NotNull String name, @Nullable OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return units.save(unit);
  }
}
