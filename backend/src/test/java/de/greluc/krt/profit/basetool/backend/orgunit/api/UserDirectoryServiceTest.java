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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
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
 * Unit tests for {@link UserDirectoryService}: listing, search and lookup return the DTOs exactly
 * as {@link UserMapper#toDto} projects them for the caller, and prime the Staffel memberships once.
 */
@ExtendWith(MockitoExtension.class)
class UserDirectoryServiceTest {

  @Mock private UserService userService;
  @Mock private UserMapper userMapper;
  @InjectMocks private UserDirectoryService service;

  private static UserDto dto(UUID id) {
    return new UserDto(
        id, "u", "U", "U", null, 5, "desc", null, null, null, false, false, true, null, List.of(),
        1L, null, null);
  }

  @Test
  void list_wrapsTheServicePageWithTheMappedDtos() {
    User entity = new User();
    UserDto dto = dto(UUID.randomUUID());
    when(userService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    PageResponse<UserDto> resp = service.list(0, 50, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
    verify(userMapper).primeStaffelMemberships(List.of(entity));
  }

  @Test
  void search_forwardsTheQueryAndMapsTheRows() {
    User entity = new User();
    UserDto dto = dto(UUID.randomUUID());
    when(userService.searchByUsername(eq("ali"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    assertSame(dto, service.search("ali", null, null, null).content().getFirst());
  }

  @Test
  void search_nullQuery_matchesAllViaEmptyFilter() {
    when(userService.searchByUsername(eq(""), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.search(null, null, null, null);

    verify(userService).searchByUsername(eq(""), any(Pageable.class));
  }

  @Test
  void get_returnsTheMappedDto() {
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto dto = dto(id);
    when(userService.findById(id)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(dto);

    assertSame(dto, service.get(id));
  }

  @Test
  void search_withASortOutsideTheWhitelist_isRejected() {
    assertThrows(IllegalArgumentException.class, () -> service.search("x", 0, 10, "password,asc"));
  }
}
