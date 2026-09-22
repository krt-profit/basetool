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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pins the no-N+1 contract of the embedded {@code UserDto} projection (REQ-DATA-003, BE-PERF-01)
 * against the real Testcontainers PostgreSQL: mapping a mission's roster or a page of users costs
 * the <em>same</em> number of SQL statements whether it holds a handful of users or dozens. Before
 * the batch primer each mapped user cost up to three statements (its Staffel memberships and two
 * squadron loads), so the count grew linearly with the roster.
 *
 * <p>Runs inside one rolled-back test transaction, as the {@code @Transactional(readOnly = true)}
 * controller handlers do, with a fresh persistence context before each measurement, and with a
 * servlet request bound so the mapper's request memo is live exactly as in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserMappingNoNPlusOneTest {

  @Autowired private MissionMapper missionMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private MissionRepository missionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Squadron staffelA;
  private Squadron staffelB;

  @BeforeEach
  void setUp() {
    staffelA = newStaffel("Perf Staffel A " + UUID.randomUUID());
    staffelB = newStaffel("Perf Staffel B " + UUID.randomUUID());
  }

  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void missionDetail_rosterMappingIsStatementConstant_fiveVersusThirtyParticipants() {
    UUID small = seedMission(5);
    UUID large = seedMission(30);

    long smallStatements = statementsToMapMission(small);
    long largeStatements = statementsToMapMission(large);

    assertEquals(
        smallStatements,
        largeStatements,
        () ->
            "mapping 30 participants issued "
                + largeStatements
                + " statements, 5 participants "
                + smallStatements
                + " (suspected per-user N+1)");
    assertTrue(largeStatements <= 12, () -> "mission detail issued " + largeStatements);
  }

  @Test
  void missionDetail_primedRosterCarriesEachUsersStaffeln() {
    UUID missionId = seedMission(3);
    entityManager.flush();
    entityManager.clear();
    bindRequest();

    MissionDto dto = missionMapper.toDto(missionRepository.findById(missionId).orElseThrow());

    assertEquals(3, dto.participants().size());
    dto.participants()
        .forEach(
            p -> {
              UserDto user = p.user();
              assertEquals(2, user.squadrons().size(), "both Staffeln survive the primer");
              assertEquals(user.squadrons().getFirst(), user.squadron(), "primary = first");
              assertTrue(user.isLogistician(), "flag OR across the Staffel rows");
            });
  }

  @Test
  void userPage_mappingIsStatementConstant_tenVersusFiftyUsers() {
    for (int i = 0; i < 50; i++) {
      newMember("perf-page-" + i + "-" + UUID.randomUUID());
    }

    long tenStatements = statementsToMapUserPage(10);
    long fiftyStatements = statementsToMapUserPage(50);

    assertEquals(
        tenStatements,
        fiftyStatements,
        () ->
            "mapping 50 users issued "
                + fiftyStatements
                + " statements, 10 users "
                + tenStatements
                + " (suspected per-user N+1)");
  }

  /** Loads the mission cold and counts the statements of load + full DTO mapping. */
  private long statementsToMapMission(UUID missionId) {
    entityManager.flush();
    entityManager.clear();
    bindRequest();
    Statistics stats = statistics();
    stats.clear();
    MissionDto dto = missionMapper.toDto(missionRepository.findById(missionId).orElseThrow());
    long count = stats.getPrepareStatementCount();
    assertTrue(!dto.participants().isEmpty());
    return count;
  }

  /** Loads one page of users cold and counts the statements of load + primed DTO mapping. */
  private long statementsToMapUserPage(int size) {
    entityManager.flush();
    entityManager.clear();
    bindRequest();
    Statistics stats = statistics();
    stats.clear();
    Page<User> page = userRepository.findAll(PageRequest.of(0, size, Sort.by("username")));
    userMapper.primeStaffelMemberships(page.getContent());
    List<UserDto> dtos = page.map(userMapper::toDto).getContent();
    long count = stats.getPrepareStatementCount();
    assertEquals(size, dtos.size());
    return count;
  }

  private Statistics statistics() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    return stats;
  }

  /** Binds a fresh servlet request, so every measurement starts with an empty request memo. */
  private static void bindRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private UUID seedMission(int participants) {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Perf Mission " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    for (int i = 0; i < participants; i++) {
      MissionParticipant participant = new MissionParticipant();
      participant.setMission(mission);
      participant.setUser(newMember("perf-roster-" + i + "-" + UUID.randomUUID()));
      mission.getParticipants().add(participant);
    }
    return missionRepository.save(mission).getId();
  }

  /** A member of both test Staffeln, Logistician in the second. */
  private User newMember(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user = userRepository.save(user);
    addMembership(user, staffelA, false);
    addMembership(user, staffelB, true);
    return user;
  }

  private void addMembership(User user, Squadron staffel, boolean logistician) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(user.getId(), staffel.getId()));
    m.setUser(user);
    m.setKind(OrgUnitKind.SQUADRON);
    m.setJoinedAt(Instant.now());
    m.setLogistician(logistician);
    membershipRepository.save(m);
  }

  private Squadron newStaffel(String name) {
    Squadron staffel = new Squadron();
    staffel.setName(name);
    staffel.setShorthand(name.substring(name.length() - 4));
    return squadronRepository.save(staffel);
  }
}
