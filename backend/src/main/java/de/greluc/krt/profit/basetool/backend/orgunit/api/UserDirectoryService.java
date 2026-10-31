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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The squadron-scoped user listing and search behind the member endpoints, with the peer redaction
 * lifted only for users of units where the caller holds the Logistician grant.
 *
 * <p>Read-only; it joins the calling transaction and needs the session open for {@link UserMapper}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDirectoryService {

  private static final Set<String> ALLOWED_SORT = Set.of("username", "email", "rank", "id");

  private final UserService userService;
  private final UserMapper userMapper;
  private final UserPeerRedactionScope peerRedactionScope;

  /**
   * Pages through all users.
   *
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression, or {@code null} for the username default
   * @return the page of peer-redacted user DTOs
   */
  public @NotNull PageResponse<UserDto> list(
      @Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "username");
    Page<User> found = userService.findAll(pageable);
    userMapper.primeStaffelMemberships(found.getContent());
    return PageResponse.of(redactForPeers(found.map(userMapper::toDto)));
  }

  /**
   * Runs the username/displayName substring search; a {@code null} query matches all.
   *
   * @param query free-text filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression, or {@code null} for the username default
   * @return the page of peer-redacted user DTOs
   */
  public @NotNull PageResponse<UserDto> search(
      @Nullable String query,
      @Nullable Integer page,
      @Nullable Integer size,
      @Nullable String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "username");
    Page<User> found = userService.searchByUsername(query == null ? "" : query, pageable);
    userMapper.primeStaffelMemberships(found.getContent());
    return PageResponse.of(redactForPeers(found.map(userMapper::toDto)));
  }

  /**
   * Returns one user; a non-admin caller asking for a user outside their squadrons always gets the
   * peer shape, regardless of role.
   *
   * @param id the user id
   * @return the user DTO, peer-redacted for cross-squadron non-admin callers
   */
  public @NotNull UserDto get(@NotNull UUID id) {
    User user = userService.findById(id);
    UserDto dto = userMapper.toDto(user);
    if (userService.isCrossSquadronForNonAdmin(user.getId())) {
      return UserDtoRedaction.toPeerShape(dto);
    }
    return peerRedactionScope.usersWithUnredactedRead(List.of(dto.id())).contains(dto.id())
        ? dto
        : UserDtoRedaction.toPeerShape(dto);
  }

  /**
   * Reduces each DTO of the page to the peer shape unless the caller holds the Logistician grant in
   * a unit of that user.
   *
   * @param users the persisted user DTOs of one page
   * @return the same page with the peer shape applied where the grant is missing
   */
  private Page<UserDto> redactForPeers(Page<UserDto> users) {
    Set<UUID> unredacted =
        peerRedactionScope.usersWithUnredactedRead(users.stream().map(UserDto::id).toList());
    return users.map(
        dto -> unredacted.contains(dto.id()) ? dto : UserDtoRedaction.toPeerShape(dto));
  }
}
