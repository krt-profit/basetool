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

package de.greluc.krt.profit.basetool.backend.orgunit.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Spezialkommando roster gates over HTTP with the real verdicts and the real pin rule
 * (REQ-ORG-005, REQ-ROLE-004, REQ-ORG-032, REQ-SEC-015): a lead changes and removes another plain
 * member's row but neither their own nor a co-lead's, needs {@code KRT Member}, and reaches the
 * roster only while the SK lies in the active context; an admin writes every row, in context too.
 */
@SpringBootTest
@Transactional
class SpecialCommandRosterGateIntegrationTest {

  private static final String KRT_MEMBER = Roles.authority(Roles.KRT_MEMBER);
  private static final String ADMIN = Roles.authority(Roles.ADMIN);
  private static final String BANK_EMPLOYEE = Roles.authority(Roles.BANK_EMPLOYEE);

  @Autowired private WebApplicationContext context;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private MockMvc mockMvc;
  private SpecialCommand sk;
  private SpecialCommand otherSk;
  private User lead;
  private User coLead;
  private User member;
  private User admin;

  @BeforeEach
  void seed() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    sk = specialCommand("Roster-SK-");
    otherSk = specialCommand("Roster-Other-SK-");
    lead = user("roster-lead-");
    coLead = user("roster-colead-");
    member = user("roster-member-");
    admin = user("roster-admin-");
    seat(lead, sk, MembershipRole.SK_LEAD);
    seat(coLead, sk, MembershipRole.SK_LEAD);
    seat(member, sk, MembershipRole.MEMBER);
    seat(admin, sk, MembershipRole.MEMBER);
    seat(lead, otherSk, MembershipRole.MEMBER);
  }

  @Test
  void aLeadChangesTheFlagsOfAPlainMemberAndRemovesThem() throws Exception {
    mockMvc.perform(flagsOf(member).with(caller(lead, KRT_MEMBER))).andExpect(status().isOk());
    mockMvc.perform(removalOf(member).with(caller(lead, KRT_MEMBER))).andExpect(status().isOk());
  }

  @Test
  void aLeadNeitherChangesNorRemovesTheirOwnRow() throws Exception {
    mockMvc.perform(flagsOf(lead).with(caller(lead, KRT_MEMBER))).andExpect(status().isForbidden());
    mockMvc
        .perform(removalOf(lead).with(caller(lead, KRT_MEMBER)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aLeadNeitherChangesNorRemovesACoLead() throws Exception {
    mockMvc
        .perform(flagsOf(coLead).with(caller(lead, KRT_MEMBER)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(removalOf(coLead).with(caller(lead, KRT_MEMBER)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aLeadWithoutKrtMemberManagesNothing() throws Exception {
    RequestPostProcessor withoutKrtMember = caller(lead, BANK_EMPLOYEE);

    mockMvc.perform(roster().with(withoutKrtMember)).andExpect(status().isForbidden());
    mockMvc.perform(flagsOf(member).with(withoutKrtMember)).andExpect(status().isForbidden());
  }

  @Test
  void aLeadReachesTheRosterOnlyWhileTheSkIsInTheActiveContext() throws Exception {
    RequestPostProcessor asLead = caller(lead, KRT_MEMBER);

    mockMvc.perform(pinnedTo(roster(), sk).with(asLead)).andExpect(status().isOk());
    mockMvc.perform(pinnedTo(skRead(), sk).with(asLead)).andExpect(status().isOk());
    mockMvc.perform(pinnedTo(roster(), otherSk).with(asLead)).andExpect(status().isForbidden());
    mockMvc.perform(pinnedTo(skRead(), otherSk).with(asLead)).andExpect(status().isForbidden());
    mockMvc
        .perform(pinnedTo(flagsOf(member), otherSk).with(asLead))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdminWritesEveryRowButOnlyInTheActiveContext() throws Exception {
    RequestPostProcessor asAdmin = caller(admin, ADMIN);

    mockMvc.perform(flagsOf(admin).with(asAdmin)).andExpect(status().isOk());
    mockMvc
        .perform(pinnedTo(removalOf(coLead), otherSk).with(asAdmin))
        .andExpect(status().isForbidden());
    mockMvc.perform(removalOf(coLead).with(asAdmin)).andExpect(status().isOk());
  }

  /**
   * Seeds an active Spezialkommando.
   *
   * @param prefix the name prefix; a random tag keeps the name unique
   * @return the persisted SK
   */
  private SpecialCommand specialCommand(String prefix) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    SpecialCommand unit = new SpecialCommand();
    unit.setName(prefix + tag);
    unit.setShorthand("RG" + tag);
    return specialCommandRepository.save(unit);
  }

  /**
   * Seeds an account.
   *
   * @param prefix the username prefix
   * @return the persisted account
   */
  private User user(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + user.getId());
    return userRepository.save(user);
  }

  /**
   * Seeds the user's membership row on the SK.
   *
   * @param user the member
   * @param unit the SK
   * @param role the rank of the row
   */
  private void seat(User user, SpecialCommand unit, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }

  /**
   * A caller with the user's account id and exactly the given authority.
   *
   * @param user the caller
   * @param authority the caller's only authority
   * @return the request post-processor carrying the JWT
   */
  private static RequestPostProcessor caller(User user, String authority) {
    return jwt()
        .jwt(j -> j.subject(user.getId().toString()))
        .authorities(new SimpleGrantedAuthority(authority));
  }

  /**
   * Sends the request with the active-context pin on the given unit.
   *
   * @param request the request
   * @param unit the pinned unit
   * @return the same request, pinned
   */
  private static MockHttpServletRequestBuilder pinnedTo(
      MockHttpServletRequestBuilder request, SpecialCommand unit) {
    return request.header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, unit.getId().toString());
  }

  /**
   * The roster read of the SK.
   *
   * @return the request
   */
  private MockHttpServletRequestBuilder roster() {
    return get("/api/v1/special-commands/{id}/members", sk.getId());
  }

  /**
   * The read of the SK itself, which heads its member page.
   *
   * @return the request
   */
  private MockHttpServletRequestBuilder skRead() {
    return get("/api/v1/special-commands/{id}", sk.getId());
  }

  /**
   * Sets the Logistiker flag on the target's row, at the row's current version.
   *
   * @param target the member whose row is written
   * @return the request
   */
  private MockHttpServletRequestBuilder flagsOf(User target) {
    Long version =
        orgUnitMembershipRepository
            .findById(new OrgUnitMembershipId(target.getId(), sk.getId()))
            .orElseThrow()
            .getVersion();
    return patch("/api/v1/special-commands/{id}/members/{userId}", sk.getId(), target.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"isLogistician\":true,\"isMissionManager\":false,\"version\":" + version + "}");
  }

  /**
   * Removes the target's row.
   *
   * @param target the member whose row is removed
   * @return the request
   */
  private MockHttpServletRequestBuilder removalOf(User target) {
    return delete("/api/v1/special-commands/{id}/members/{userId}", sk.getId(), target.getId());
  }
}
