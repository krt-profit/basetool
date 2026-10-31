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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link UserDirectoryService}: the listing and search wrap the service page, the
 * peer redaction is lifted only for users the caller holds the Logistician grant for, and a user
 * outside the caller's squadrons is always peer-shaped.
 */
@ExtendWith(MockitoExtension.class)
class UserDirectoryServiceTest {

  @Mock private UserService userService;
  @Mock private UserMapper userMapper;
  @Mock private UserPeerRedactionScope peerRedactionScope;
  @InjectMocks private UserDirectoryService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    lenient()
        .when(peerRedactionScope.usersWithUnredactedRead(any()))
        .thenAnswer(inv -> new HashSet<UUID>(inv.<java.util.Collection<UUID>>getArgument(0)));
  }

  @Test
  void getAllUsers_wrapsServicePageIntoPageResponse() {
    User entity = new User();
    UserDto dto = mockDto(UUID.randomUUID());
    when(userService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    PageResponse<UserDto> resp = service.list(0, 50, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void searchUsers_forwardsQueryToService() {
    when(userService.searchByUsername(eq("ali"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.search("ali", null, null, null);

    verify(userService).searchByUsername(eq("ali"), any(Pageable.class));
  }

  @Test
  void searchUsers_nullQuery_matchesAllViaEmptyFilter() {
    when(userService.searchByUsername(eq(""), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.search(null, null, null, null);

    verify(userService).searchByUsername(eq(""), any(Pageable.class));
  }

  @Test
  void searchUsers_wrapsServicePageIntoPageResponse() {
    User entity = new User();
    UserDto dto = mockDto(UUID.randomUUID());
    when(userService.searchByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    PageResponse<UserDto> resp = service.search("x", 0, 50, null);

    assertEquals(1, resp.content().size());
  }

  @Test
  void getUserById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto dto = mockDto(id);
    when(userService.findById(id)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(dto);

    UserDto result = service.get(id);

    assertSame(dto, result);
  }

  @Test
  void getUserById_nonOfficerCaller_redactsPii() {
    doReturn(java.util.Set.of()).when(peerRedactionScope).usersWithUnredactedRead(any());
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto fullDto = fullPiiUserDto(id);
    when(userService.findById(id)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = service.get(id);

    assertNotSame(fullDto, result);
    assertEquals(id, result.id());
    assertEquals("bob.callsign", result.username());
    assertEquals("Bob Display", result.displayName());
    assertNull(result.email(), "peer view must not expose email");
    assertNull(result.roles(), "peer view must not expose roles");
    assertNull(result.permissions(), "peer view must not expose permissions");
  }

  @Test
  void searchUsers_nonOfficerCaller_redactsEveryRowPii() {
    doReturn(java.util.Set.of()).when(peerRedactionScope).usersWithUnredactedRead(any());
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto fullDto = fullPiiUserDto(id);
    when(userService.searchByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    PageResponse<UserDto> resp = service.search("bob", 0, 50, null);

    UserDto redacted = resp.content().getFirst();
    assertNull(redacted.email());
  }

  @Test
  void getUserById_officerFromForeignSquadron_redactsPii() {
    UUID userId = UUID.randomUUID();
    when(userService.isCrossSquadronForNonAdmin(userId)).thenReturn(true);

    User entity = new User();
    entity.setId(userId);
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = service.get(userId);

    assertEquals("bob.callsign", result.username());
    assertNull(result.email(), "cross-squadron non-admin must not see email");
    assertNull(result.joinDate(), "cross-squadron non-admin must not see joinDate");
  }

  @Test
  void getUserById_unassignedUser_redactsPiiForNonAdmin() {
    UUID userId = UUID.randomUUID();
    User entity = new User();
    entity.setId(userId);
    when(userService.isCrossSquadronForNonAdmin(userId)).thenReturn(true);
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = service.get(userId);

    assertNull(result.email());
  }

  @Test
  void getUserById_sameSquadronOfficer_keepsPii() {
    UUID userId = UUID.randomUUID();
    when(userService.isCrossSquadronForNonAdmin(userId)).thenReturn(false);

    User entity = new User();
    entity.setId(userId);
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = service.get(userId);

    assertSame(fullDto, result, "same-squadron officer must see the original DTO unredacted");
  }

  @Test
  void list_logisticianInAOnly_redactsTheUserOfB() {
    UUID inA = UUID.randomUUID();
    UUID inB = UUID.randomUUID();
    User entityA = new User();
    User entityB = new User();
    when(userService.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entityA, entityB)));
    when(userMapper.toDto(entityA)).thenReturn(fullPiiUserDto(inA));
    when(userMapper.toDto(entityB)).thenReturn(fullPiiUserDto(inB));
    doReturn(java.util.Set.of(inA)).when(peerRedactionScope).usersWithUnredactedRead(any());

    PageResponse<UserDto> resp = service.list(0, 50, null);

    assertEquals("bob@example.invalid", resp.content().get(0).email());
    assertNull(resp.content().get(1).email());
    assertNull(resp.content().get(1).roles());
    assertNull(resp.content().get(1).joinDate());
  }

  @Test
  void search_logisticianInAOnly_redactsTheUserOfB() {
    UUID inB = UUID.randomUUID();
    User entityB = new User();
    when(userService.searchByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entityB)));
    when(userMapper.toDto(entityB)).thenReturn(fullPiiUserDto(inB));
    doReturn(java.util.Set.of()).when(peerRedactionScope).usersWithUnredactedRead(any());

    assertNull(service.search("bob", 0, 50, null).content().getFirst().roles());
  }

  @Test
  void getUserById_userOutsideTheGrantUnits_isPeerShaped() {
    UUID userId = UUID.randomUUID();
    User entity = new User();
    entity.setId(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullPiiUserDto(userId));
    doReturn(java.util.Set.of()).when(peerRedactionScope).usersWithUnredactedRead(any());

    assertNull(service.get(userId).email());
  }

  private static UserDto fullPiiUserDto(UUID id) {
    return new UserDto(
        id,
        "bob.callsign",
        "Bob Display",
        "Bob",
        "bob@example.invalid",
        5,
        "some desc",
        java.util.Set.of("ROLE_KRT_MEMBER"),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }

  @Test
  void list_nonOfficerCaller_redactsEveryRow() {
    doReturn(java.util.Set.of()).when(peerRedactionScope).usersWithUnredactedRead(any());
    UUID id = UUID.randomUUID();
    User entity = new User();
    when(userService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(fullPiiUserDto(id));

    PageResponse<UserDto> resp = service.list(0, 50, null);

    assertNull(resp.content().getFirst().email());
    assertNull(resp.content().getFirst().roles());
  }

  @Test
  void list_primesTheStaffelMembershipsOnce() {
    User entity = new User();
    when(userService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(mockDto(UUID.randomUUID()));

    service.list(null, null, null);

    verify(userMapper).primeStaffelMemberships(List.of(entity));
  }

  @Test
  void search_withASortOutsideTheWhitelist_isRejected() {
    assertThrows(IllegalArgumentException.class, () -> service.search("x", 0, 10, "password,asc"));
  }

  private static UserDto mockDto(UUID id) {
    return new UserDto(
        id,
        "u",
        "U",
        "U",
        "u@example.com",
        5,
        "desc",
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }
}
