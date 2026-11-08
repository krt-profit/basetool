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

package de.greluc.krt.profit.basetool.backend.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEvent;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.IdentityMetricNames;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Assigning and withdrawing the four database-held roles through {@code /api/v1/users/{id}/roles}
 * (REQ-SEC-088): the role-set counter, who may assign what, the last-admin rule, the departure, the
 * audit trail, the notice and the counter.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class UserRoleControllerIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private EntityManager entityManager;
  @Autowired private ApplicationEvents events;

  private MockMvc mockMvc;
  private User admin;
  private User member;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    entityManager
        .createNativeQuery(
            "UPDATE app_user SET in_keycloak = FALSE WHERE id IN (SELECT ur.user_id FROM"
                + " user_roles ur JOIN role r ON r.id = ur.role_id WHERE r.code = 'ADMIN')")
        .executeUpdate();
    admin = saveUser("role-admin", Roles.ADMIN);
    member = saveUser("role-member", Roles.KRT_MEMBER);
  }

  @Test
  @DisplayName("an admin assigns a role: it is stored, the counter moves and the user row does not")
  void adminAssignsARole() throws Exception {
    long userVersion = userRepository.findById(member.getId()).orElseThrow().getVersion();

    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.roles[0]").value(Roles.KRT_MEMBER))
        .andExpect(jsonPath("$.roles[1]").value(Roles.BANK_EMPLOYEE));

    entityManager.flush();
    entityManager.clear();
    User stored = userRepository.findById(member.getId()).orElseThrow();
    assertThat(stored.getRoles()).extracting(Role::getCode).contains(Roles.BANK_EMPLOYEE);
    assertThat(stored.getRolesVersion()).isEqualTo(1L);
    assertThat(stored.getVersion()).isEqualTo(userVersion);
  }

  @Test
  @DisplayName("an assignment is audited, counted and announced to the member")
  void assignmentIsRecorded() throws Exception {
    double before = changes(Roles.BANK_EMPLOYEE, "assigned");

    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isOk());

    AuditEvent event = onlyEvent(AuditEventType.USER_ROLE_ASSIGNED);
    assertThat(event.getActorUserId()).isEqualTo(admin.getId());
    assertThat(event.getSubjectId()).isEqualTo(member.getId());
    assertThat(event.getDetails()).contains("role=" + Roles.BANK_EMPLOYEE);
    assertThat(changes(Roles.BANK_EMPLOYEE, "assigned")).isEqualTo(before + 1);
    List<NoticeEvent> notices = roleNotices();
    assertThat(notices).hasSize(1);
    NoticeEvent notice = notices.getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.USER_ROLE_CHANGED);
    assertThat(notice.contextRecipientUserId()).isEqualTo(member.getId());
    assertThat(notice.actorSub()).isEqualTo(admin.getId());
    assertThat(notice.renderParams())
        .containsEntry("roleCode", Roles.BANK_EMPLOYEE)
        .containsEntry("changeCode", "ASSIGNED");
  }

  @Test
  @DisplayName("a withdrawal removes the role and is audited as such")
  void adminWithdrawsARole() throws Exception {
    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isOk());

    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, false, 1L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.roles.length()").value(1));

    assertThat(onlyEvent(AuditEventType.USER_ROLE_WITHDRAWN).getDetails())
        .contains("role=" + Roles.BANK_EMPLOYEE);
  }

  @Test
  @DisplayName("a stale role-set counter is refused with 409 and changes nothing")
  void staleCounterConflicts() throws Exception {
    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isOk());

    change(asAdmin(admin), member, Roles.BANK_MANAGEMENT, true, 0L)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @DisplayName("a change that leaves the role set as it is writes and tells nothing")
  void unchangedIsSilent() throws Exception {
    change(asAdmin(admin), member, Roles.KRT_MEMBER, true, 0L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));

    assertThat(roleNotices()).isEmpty();
  }

  @Test
  @DisplayName("the last active admin cannot give up Admin")
  void lastAdminKeepsAdmin() throws Exception {
    change(asAdmin(admin), admin, Roles.ADMIN, false, 0L)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

    User other = saveUser("role-second-admin", Roles.ADMIN);
    change(asAdmin(admin), other, Roles.ADMIN, false, 0L).andExpect(status().isOk());
  }

  @Test
  @DisplayName("withdrawing KRT Member is a departure")
  void withdrawingKrtMemberIsADeparture() throws Exception {
    change(asAdmin(admin), member, Roles.KRT_MEMBER, false, 0L).andExpect(status().isOk());

    assertThat(events.stream(MemberDepartedEvent.class))
        .containsExactly(
            new MemberDepartedEvent(
                member.getId(), MemberDepartedEvent.REASON_MEMBER_ROLE_WITHDRAWN));
  }

  @Test
  @DisplayName("the bank management assigns and withdraws Bank Employee and nothing else")
  void bankManagementAssignsOnlyBankEmployee() throws Exception {
    User manager = saveUser("role-bank-manager", Roles.BANK_MANAGEMENT);

    change(asBankManagement(manager), member, Roles.BANK_EMPLOYEE, true, 0L)
        .andExpect(status().isOk());
    change(asBankManagement(manager), member, Roles.BANK_MANAGEMENT, true, 1L)
        .andExpect(status().isForbidden());
    change(asBankManagement(manager), member, Roles.ADMIN, true, 1L)
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("the bank management reads only the Bank Employee part of a role set")
  void bankManagementSeesOnlyBankEmployee() throws Exception {
    User manager = saveUser("role-bank-manager", Roles.BANK_MANAGEMENT);
    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isOk());

    mockMvc
        .perform(get(url(member)).with(asBankManagement(manager)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.roles.length()").value(1))
        .andExpect(jsonPath("$.roles[0]").value(Roles.BANK_EMPLOYEE));
  }

  @Test
  @DisplayName("the bank management lists the Bank Employees with their counters")
  void bankManagementListsTheHolders() throws Exception {
    User manager = saveUser("role-bank-manager", Roles.BANK_MANAGEMENT);
    change(asAdmin(admin), member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/users/role-holders")
                .param("role", Roles.BANK_EMPLOYEE)
                .with(asBankManagement(manager)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.userId == '" + member.getId() + "')].rolesVersion").value(1));
  }

  @Test
  @DisplayName("a member can neither read nor change role sets")
  void memberIsRefused() throws Exception {
    RequestPostProcessor asMember =
        jwt()
            .jwt(builder -> builder.subject(member.getId().toString()))
            .authorities(new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)));

    mockMvc.perform(get(url(admin)).with(asMember)).andExpect(status().isForbidden());
    change(asMember, member, Roles.BANK_EMPLOYEE, true, 0L).andExpect(status().isForbidden());
  }

  private @NotNull ResultActions change(
      @NotNull RequestPostProcessor caller,
      @NotNull User target,
      @NotNull String role,
      boolean assigned,
      long version)
      throws Exception {
    return mockMvc.perform(
        put(url(target) + "/" + role)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assigned\":" + assigned + ",\"version\":" + version + "}"));
  }

  private static @NotNull String url(@NotNull User target) {
    return "/api/v1/users/" + target.getId() + "/roles";
  }

  private static @NotNull RequestPostProcessor asAdmin(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)));
  }

  private static @NotNull RequestPostProcessor asBankManagement(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(
            new SimpleGrantedAuthority(Roles.authority(Roles.BANK_MANAGEMENT)),
            new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)));
  }

  private @NotNull List<NoticeEvent> roleNotices() {
    return events.stream(NoticeEvent.class)
        .filter(notice -> notice.eventType() == NotificationEventType.USER_ROLE_CHANGED)
        .toList();
  }

  private double changes(@NotNull String role, @NotNull String action) {
    Counter counter =
        meterRegistry
            .find(IdentityMetricNames.USER_ROLE_CHANGES)
            .tag(IdentityMetricNames.TAG_ROLE, role)
            .tag(IdentityMetricNames.TAG_ACTION, action)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private @NotNull AuditEvent onlyEvent(@NotNull AuditEventType type) {
    List<AuditEvent> matching =
        auditEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type)
            .filter(e -> member.getId().equals(e.getTargetUserId()))
            .toList();
    assertThat(matching).hasSize(1);
    return matching.getFirst();
  }

  private @NotNull User saveUser(@NotNull String username, @NotNull String roleCode) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(roleCode).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }
}
