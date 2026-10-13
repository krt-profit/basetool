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

package de.greluc.krt.profit.basetool.backend.identity.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link UserReferenceSearchService}: a null query matches all, the page request is
 * resolved against the reference sort whitelist, and the service page is wrapped.
 */
@ExtendWith(MockitoExtension.class)
class UserReferenceSearchServiceTest {

  @Mock private UserService userService;
  @InjectMocks private UserReferenceSearchService service;

  @Test
  void search_nullQuery_matchesAllViaEmptyFilter() {
    when(userService.searchReferencesByUsername(eq(""), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.search(null, null, null, null);

    verify(userService).searchReferencesByUsername(eq(""), any(Pageable.class));
  }

  @Test
  void search_wrapsTheServicePageIntoAPageResponse() {
    UserReferenceDto ref = new UserReferenceDto(UUID.randomUUID(), "alice", "Alice", "Alice", 5);
    when(userService.searchReferencesByUsername(eq("ali"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(ref)));

    PageResponse<UserReferenceDto> result = service.search("ali", 0, 50, null);

    assertEquals(1, result.totalElements());
    assertSame(ref, result.content().getFirst());
  }

  @Test
  void search_resolvesThePageRequestAgainstTheReferenceWhitelist() {
    when(userService.searchReferencesByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.search("x", 2, 10, "rank,desc");

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(userService).searchReferencesByUsername(eq("x"), pageable.capture());
    assertEquals(2, pageable.getValue().getPageNumber());
    assertEquals(10, pageable.getValue().getPageSize());
    assertNotNull(pageable.getValue().getSort().getOrderFor("rank"));
  }

  @Test
  void search_withASortOutsideTheWhitelist_isRejected() {
    assertThrows(IllegalArgumentException.class, () -> service.search("x", 0, 10, "email,asc"));
  }
}
