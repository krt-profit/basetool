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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class UserMapperTest {

  private UserMapper mapper;
  private OrgUnitMembershipRepository membershipRepository;
  private SquadronRepository squadronRepository;
  private OrgUnitRepository orgUnitRepository;

  @BeforeEach
  void setUp() {
    // Post-R9 D3 (V101): the mapper derives squadron + flag fields from the membership table. Wire
    // the membership repository plus a real StaffelMembershipResolver (backed by the repo mocks —
    // the polymorphic OrgUnitRepository serves the multi-row batch, the SquadronRepository the
    // single-row existsById fast path) so the name-sort path is exercised end-to-end; we are not
    // running inside a Spring context.
    mapper = Mappers.getMapper(UserMapper.class);
    membershipRepository = mock(OrgUnitMembershipRepository.class);
    squadronRepository = mock(SquadronRepository.class);
    orgUnitRepository = mock(OrgUnitRepository.class);
    ReflectionTestUtils.setField(mapper, "membershipRepository", membershipRepository);
    ReflectionTestUtils.setField(
        mapper,
        "staffelMembershipResolver",
        new StaffelMembershipResolver(squadronRepository, orgUnitRepository));
  }

  /** Builds a {@code SQUADRON}-kind membership row pointing the user at the given squadron. */
  private static OrgUnitMembership staffelRow(UUID userId, UUID squadronId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  /** Builds a squadron fixture with the given id, name and shorthand. */
  private static Squadron squadron(UUID id, String name, String shorthand) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName(name);
    s.setShorthand(shorthand);
    return s;
  }

  @Test
  void toDto_shouldMapBasicFieldsAndAggregates() {
    Role admin = new Role();
    admin.setName("ADMIN");
    admin.setPermissions(new HashSet<>(Set.of("USER_MANAGE", "ROLE_ASSIGN")));

    Role officer = new Role();
    officer.setName("OFFICER");
    officer.setPermissions(new HashSet<>(Set.of("MISSION_MANAGE", "USER_MANAGE")));

    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("jdoe");
    user.setEmail("jdoe@example.com");
    user.setRank(5);
    user.setDescription("desc");
    user.getRoles().add(admin);
    user.getRoles().add(officer);
    // No membership rows wired — the mapper projects squadron / isLogistician / isMissionManager
    // as null / false respectively for this fixture.
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    UserDto dto = mapper.toDto(user);
    assertNotNull(dto);
    assertEquals(user.getId(), dto.id());
    assertEquals("jdoe", dto.username());
    // PII: toDto deliberately omits email (it is re-added only on the /me self path via
    // UserController.withSelfEmail). It must be null on every projection this mapper produces.
    assertNull(dto.email());
    assertEquals(5, dto.rank());
    assertEquals("desc", dto.description());
    assertEquals(Set.of("ADMIN", "OFFICER"), dto.roles());
    assertEquals(Set.of("USER_MANAGE", "ROLE_ASSIGN", "MISSION_MANAGE"), dto.permissions());
    // No Discord id wired on the fixture -> the link indicator is false (REQ-SEC-019).
    assertEquals(Boolean.FALSE, dto.discordLinked());
  }

  @Test
  void toDto_withDiscordUserId_setsDiscordLinkedTrue() {
    // covers REQ-SEC-019 / REQ-DATA-006 — a federated Discord account surfaces as discordLinked,
    // while the raw snowflake itself is never copied onto the DTO.
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("linked");
    user.setDiscordUserId("123456789012345678");
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    UserDto dto = mapper.toDto(user);

    assertEquals(Boolean.TRUE, dto.discordLinked());
  }

  @Test
  void toDto_withBlankDiscordUserId_setsDiscordLinkedFalse() {
    // A blank (whitespace-only) Discord id is treated as "not linked" — no symbol shown.
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("blank");
    user.setDiscordUserId("   ");
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    UserDto dto = mapper.toDto(user);

    assertEquals(Boolean.FALSE, dto.discordLinked());
  }

  @Test
  void toDto_withNullRoles_shouldReturnEmptySets() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("empty");
    user.setEmail(null);
    user.setRank(null);
    user.setDescription(null);
    user.setRoles(null);
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    UserDto dto = mapper.toDto(user);
    assertNotNull(dto);
    assertNotNull(dto.roles());
    assertTrue(dto.roles().isEmpty());
    assertNotNull(dto.permissions());
    assertTrue(dto.permissions().isEmpty());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
  }

  @Test
  void toDto_twoStaffeln_mapsNameSortedSquadronsAndPrimary() {
    // REQ-ORG-017: a member may hold up to two Staffeln. The mapper projects them name-sorted via
    // the shared StaffelMembershipResolver, with squadron == the name-sorted primary (first).
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("dual");

    UUID alphaId = UUID.randomUUID();
    UUID bravoId = UUID.randomUUID();
    // Rows + squadron entities returned in non-alphabetical order to prove the sort — not the
    // repository/input order — decides the primary.
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelRow(user.getId(), bravoId), staffelRow(user.getId(), alphaId)));
    when(orgUnitRepository.findAllById(any()))
        .thenReturn(
            List.of(
                (OrgUnit) squadron(bravoId, "Bravo", "BRV"), squadron(alphaId, "Alpha", "ALP")));

    UserDto dto = mapper.toDto(user);

    assertEquals(2, dto.squadrons().size());
    assertEquals("Alpha", dto.squadrons().get(0).name());
    assertEquals("Bravo", dto.squadrons().get(1).name());
    assertEquals(alphaId, dto.squadron().id());
    assertEquals("Alpha", dto.squadron().name());
  }

  @Test
  void toDto_withinRequest_loadsStaffelMembershipOncePerUser() {
    // The three derived-field resolvers (squadron / isLogistician / isMissionManager) each need the
    // user's Staffel membership. Within an HTTP request the lookup is memoised per user, so the
    // derived JPQL query runs once instead of three times per toDto.
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("memo");
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      mapper.toDto(user);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    verify(membershipRepository, times(1))
        .findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
  }

  @Test
  void toDto_withoutRequestScope_fallsBackToDirectQuery() {
    // Outside an HTTP request (e.g. a scheduled task) there is no request scope to memoise on, so
    // each of the four membership-derived resolvers (squadron, squadrons, isLogistician,
    // isMissionManager) issues its own lookup — the fallback must not throw.
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("noRequest");
    when(membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    UserDto dto = mapper.toDto(user);

    assertNotNull(dto);
    verify(membershipRepository, times(4))
        .findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
  }
}
