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

package de.greluc.krt.profit.basetool.backend.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionCommands;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the operation module's externally visible contract: the scope and participant gates of
 * {@code /api/v1/operations/{id}/**} per caller, and the delete that detaches the linked missions
 * without touching their section counters and records {@code OPERATION_DELETED} in the same
 * transaction (REQ-AUDIT-001).
 */
@SpringBootTest
@Transactional
class OperationModuleContractTest {

  private static final String OPERATIONS = "/api/v1/operations/";

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private OperationRepository operationRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  @Autowired private EntityManager entityManager;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private MissionCommands missionCommands;

  @Autowired private PlatformTransactionManager transactionManager;

  private MockMvc mockMvc;

  private Squadron home;

  private Squadron foreign;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = squadron("OpHome-" + tag, "H" + tag);
    foreign = squadron("OpForeign-" + tag, "F" + tag);
  }

  @Test
  void anUnknownOperationIsForbiddenOnEveryGatedEndpoint() throws Exception {
    User admin = user("op-admin", home);
    UUID unknown = UUID.randomUUID();
    RequestPostProcessor caller = as(admin, "ROLE_ADMIN", "ROLE_MISSION_MANAGER", "ROLE_MEMBER");

    for (String path : readPaths(unknown)) {
      mockMvc.perform(get(path).with(caller)).andExpect(status().isForbidden());
    }
    mockMvc
        .perform(
            put(OPERATIONS + unknown)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"status\":\"PLANNED\",\"version\":0}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(OPERATIONS + unknown).with(caller)).andExpect(status().isForbidden());
  }

  @Test
  void aMemberOfTheOwningUnitReadsTheOperationAndItsLedger() throws Exception {
    User member = user("op-member", home);
    Operation operation = operation(home);

    for (String path : readPaths(operation.getId())) {
      mockMvc.perform(get(path).with(as(member, "ROLE_MEMBER"))).andExpect(status().isOk());
    }
  }

  @Test
  void aForeignMemberIsForbiddenWithoutParticipation() throws Exception {
    User outsider = user("op-outsider", foreign);
    Operation operation = operation(home);

    for (String path : readPaths(operation.getId())) {
      mockMvc
          .perform(get(path).with(as(outsider, "ROLE_MEMBER")))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void aForeignParticipantReadsTheOperationButNotItsLedger() throws Exception {
    User participant = user("op-participant", foreign);
    Operation operation = operation(home);
    Mission mission = mission(operation);
    MissionParticipant row = new MissionParticipant();
    row.setMission(mission);
    row.setUser(participant);
    missionParticipantRepository.save(row);
    UUID id = operation.getId();

    mockMvc
        .perform(get(OPERATIONS + id).with(as(participant, "ROLE_MEMBER")))
        .andExpect(status().isOk());
    mockMvc
        .perform(get(OPERATIONS + id + "/payouts").with(as(participant, "ROLE_MEMBER")))
        .andExpect(status().isOk());
    for (String ledger :
        List.of(
            OPERATIONS + id + "/finances",
            OPERATIONS + id + "/finance-summary",
            OPERATIONS + id + "/finances/" + mission.getId())) {
      mockMvc
          .perform(get(ledger).with(as(participant, "ROLE_MEMBER")))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void aForeignMissionManagerIsForbiddenButMayEditAnOwnerlessOperation() throws Exception {
    User manager = user("op-foreign-manager", foreign);
    Operation owned = operation(home);
    Operation ownerless = operation(null);
    RequestPostProcessor caller = as(manager, "ROLE_MEMBER", "ROLE_MISSION_MANAGER");

    mockMvc.perform(update(owned).with(caller)).andExpect(status().isForbidden());
    mockMvc.perform(update(ownerless).with(caller)).andExpect(status().isOk());
  }

  @Test
  void deleteDetachesTheMissionsWithoutBumpingACounterAndAuditsInTheSameTransaction()
      throws Exception {
    User admin = user("op-delete-admin", home);
    Operation operation = operation(home);
    Mission first = mission(operation);
    Mission second = mission(operation);
    entityManager.flush();
    Map<String, Object> before = counters(first.getId());

    mockMvc
        .perform(delete(OPERATIONS + operation.getId()).with(as(admin, "ROLE_ADMIN")))
        .andExpect(status().isNoContent());
    entityManager.flush();
    entityManager.clear();

    assertThat(operationRepository.findById(operation.getId())).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM mission WHERE id IN (?, ?) AND operation_id IS NULL",
                Long.class,
                first.getId(),
                second.getId()))
        .isEqualTo(2L);
    assertThat(counters(first.getId())).isEqualTo(before);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT event_type FROM audit_event WHERE subject_id = ?",
                String.class,
                operation.getId()))
        .containsExactly("OPERATION_DELETED");
  }

  @Test
  void theDetachCommandRefusesToRunOutsideTheCallersTransaction() {
    TransactionTemplate withoutTransaction = new TransactionTemplate(transactionManager);
    withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    assertThatThrownBy(
            () ->
                withoutTransaction.executeWithoutResult(
                    _ -> missionCommands.detachFromOperation(UUID.randomUUID())))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private Map<String, Object> counters(UUID missionId) {
    return jdbcTemplate.queryForMap(
        "SELECT version, core_version, schedule_version, flags_version FROM mission WHERE id = ?",
        missionId);
  }

  private static List<String> readPaths(UUID id) {
    return List.of(
        OPERATIONS + id,
        OPERATIONS + id + "/finances",
        OPERATIONS + id + "/finance-summary",
        OPERATIONS + id + "/payouts");
  }

  private static MockHttpServletRequestBuilder update(Operation operation) {
    return put(OPERATIONS + operation.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"name\":\"Renamed\",\"status\":\"ACTIVE\",\"version\":"
                + operation.getVersion()
                + "}");
  }

  private Squadron squadron(String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadronRepository.save(squadron);
  }

  private User user(String username, Squadron unit) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 6));
    user = userRepository.save(user);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  private Operation operation(Squadron owner) {
    Operation operation = new Operation();
    operation.setName("Contract Op " + UUID.randomUUID());
    operation.setStatus(OperationStatus.ACTIVE);
    operation.setOwningOrgUnit(owner);
    return operationRepository.saveAndFlush(operation);
  }

  private Mission mission(Operation operation) {
    Mission mission = new Mission();
    mission.setName("Contract Mission " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setOperation(operation);
    mission.setOwningOrgUnit(operation.getOwningOrgUnit());
    mission = missionRepository.save(mission);
    operation.getMissions().add(mission);
    return mission;
  }

  private static RequestPostProcessor as(User user, String... authorities) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(
            Arrays.stream(authorities).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
  }
}
