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

package de.greluc.krt.profit.basetool.backend.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LogisticianRoleTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private SpecialCommandRepository specialCommandRepository;

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void converterShouldAddLogisticianRole() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("logistician_user");
    userRepository.save(user);
    userRepository.flush();
    saveLogisticianMembership(userId, user, true);

    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .claim("preferred_username", "logistician_user")
            .build();

    Collection<GrantedAuthority> authorities = converter.convert(jwt);
    assertTrue(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "Should have ROLE_LOGISTICIAN");
  }

  /**
   * A user whose Staffel membership has {@code is_logistician = true} gets the flat {@code
   * ROLE_LOGISTICIAN}.
   */
  @Test
  void converterPromotesLogistician_whenMembershipFlagSet() {
    UUID userId = UUID.randomUUID();

    User user = new User();
    user.setId(userId);
    user.setUsername("membership_logistician");
    userRepository.save(user);
    userRepository.flush();

    saveLogisticianMembership(userId, user, true);

    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .claim("preferred_username", "membership_logistician")
            .build();

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "Membership-level is_logistician=true must promote to ROLE_LOGISTICIAN.");
  }

  /**
   * A user whose Staffel membership has {@code is_logistician = false} does not get the flat role.
   */
  @Test
  void converterDoesNotPromote_whenMembershipFlagFalse() {
    UUID userId = UUID.randomUUID();

    User user = new User();
    user.setId(userId);
    user.setUsername("not_logistician");
    userRepository.save(user);
    userRepository.flush();

    saveLogisticianMembership(userId, user, false);

    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .claim("preferred_username", "not_logistician")
            .build();

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "Membership-level flag is the single source of truth — false must NOT promote.");
  }

  /**
   * An SK lead gets the flat {@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER} and the
   * contextual {@code LOGISTICIAN@skId} / {@code MISSION_MANAGER@skId}, even with both membership
   * flags {@code false}.
   */
  @Test
  void converterPromotesSkLead_asSkLogisticianAndMissionManager() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("sk_lead_user");
    userRepository.save(user);
    userRepository.flush();
    UUID skId = saveSkLeadMembership(userId, user);

    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .claim("preferred_username", "sk_lead_user")
            .build();

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "An SK lead must get the flat ROLE_LOGISTICIAN.");
    assertTrue(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_MISSION_MANAGER")),
        "An SK lead must get the flat ROLE_MISSION_MANAGER.");
    assertTrue(
        authorities.stream()
            .anyMatch(
                a ->
                    a instanceof OrgUnitContextualAuthority c
                        && "LOGISTICIAN".equals(c.roleName())
                        && skId.equals(c.orgUnitId())),
        "An SK lead must get the contextual LOGISTICIAN authority on that SK.");
    assertTrue(
        authorities.stream()
            .anyMatch(
                a ->
                    a instanceof OrgUnitContextualAuthority c
                        && "MISSION_MANAGER".equals(c.roleName())
                        && skId.equals(c.orgUnitId())),
        "An SK lead must get the contextual MISSION_MANAGER authority on that SK.");
  }

  @Test
  void adminShouldAccessInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void officerShouldAccessInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isOk());
  }

  @Test
  void memberWithLogisticianRoleShouldAccessInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().isOk());
  }

  @Test
  void realRequestShouldHaveLogisticianRole() throws Exception {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("test_logistician");
    userRepository.save(user);
    userRepository.flush();
    saveLogisticianMembership(userId, user, true);

    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(userId.toString())
                                    .claim("preferred_username", "test_logistician"))
                        .authorities(converter)))
        .andDo(print())
        .andExpect(status().isOk());
  }

  @Test
  void memberWithoutFlagShouldAccessInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void memberWithoutFlagShouldAccessMaterialInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/material/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void memberWithoutFlagShouldAccessAllInventory() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/all")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  /** Saves an IRIDIUM membership for the test user carrying the given Logistician flag. */
  private void saveLogisticianMembership(UUID userId, User user, boolean isLogistician) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, Squadron.IRIDIUM_ID));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setJoinedAt(java.time.Instant.now());
    membership.setLogistician(isLogistician);
    orgUnitMembershipRepository.save(membership);
    orgUnitMembershipRepository.flush();
  }

  /**
   * Persists a fresh SK and a lead membership on it ({@code is_lead = true}, {@code is_logistician
   * = false}) for the given user, returning the SK's id. {@code is_lead} is only valid on SK
   * memberships (DB CHECK), so the org unit must be a Spezialkommando.
   */
  private UUID saveSkLeadMembership(UUID userId, User user) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    SpecialCommand sk = new SpecialCommand();
    sk.setName("Lead-SK-" + tag);
    sk.setShorthand("L" + tag);
    sk = specialCommandRepository.save(sk);
    specialCommandRepository.flush();

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, sk.getId()));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SPECIAL_COMMAND);
    membership.setJoinedAt(java.time.Instant.now());
    membership.setLogistician(false);
    membership.setRole(MembershipRole.SK_LEAD);
    orgUnitMembershipRepository.save(membership);
    orgUnitMembershipRepository.flush();
    return sk.getId();
  }
}
