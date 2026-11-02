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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserDirectoryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the local {@code app_user} mirror: the user list and search, a user by id, the
 * caller's own record and description, and the admin attribute edit.
 *
 * <p>{@code /me} endpoints derive the user from the JWT, never from the URL; {@code /attributes} is
 * admin-scoped. The class-level {@link Transactional} keeps the session open for the {@link
 * UserMapper} projection.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class UserProfileController {

  private final UserService userService;
  private final UserMapper userMapper;
  private final UserDirectoryService userDirectoryService;

  /**
   * Paged user list. Open to every authenticated member because the participant pickers in the
   * mission editor consume it.
   *
   * @return paged user DTOs
   */
  @GetMapping("/users")
  @PreAuthorize("hasAnyRole('" + Roles.ADMIN + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserDto> getAllUsers(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userDirectoryService.list(page, size, sort);
  }

  /**
   * Paged username/displayName substring search.
   *
   * @return paged user DTOs
   */
  @GetMapping("/users/search")
  @PreAuthorize("hasAnyRole('" + Roles.ADMIN + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserDto> searchUsers(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userDirectoryService.search(query, page, size, sort);
  }

  /**
   * Returns a user by id; a non-admin caller asking for a user outside their squadrons always gets
   * the peer-redacted shape, regardless of role.
   *
   * @param id user id
   * @return the user DTO, peer-redacted for cross-squadron non-admin callers
   */
  @GetMapping("/users/{id}")
  @PreAuthorize("hasAnyRole('" + Roles.ADMIN + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public UserDto getUserById(@PathVariable @NotNull UUID id) {
    return userDirectoryService.get(id);
  }

  /**
   * Returns the calling user's own record, derived from the JWT subject.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}
   * @return the user DTO
   */
  @NotNull
  @GetMapping("/users/me")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public UserDto getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return UserDtoRedaction.withSelfEmail(userMapper.toDto(me), me);
  }

  /**
   * Updates the calling user's own description + displayName. The JWT identifies the row — no
   * impersonation possible.
   *
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   */
  @NotNull
  @PutMapping("/users/me/description")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateMyDescription(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid UserDescriptionRequest request) {
    User me =
        userService.updateUserDescription(
            userService.getUserIdFromJwt(jwt),
            request.description(),
            request.displayName(),
            request.version());
    return UserDtoRedaction.withSelfEmail(userMapper.toDto(me), me);
  }

  /**
   * Admin/officer-only: edits an arbitrary user's attributes (rank, description, displayName,
   * joinDate). Carries optimistic-lock version in the body so concurrent admin edits surface a 409
   * instead of silently overwriting.
   *
   * @param id user id
   * @param request typed body (NOT query params — keeps user values out of access logs)
   * @return the persisted DTO
   */
  @PutMapping("/users/{id}/attributes")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public UserDto updateUserAttributes(
      @PathVariable @NotNull UUID id, @NotNull @RequestBody @Valid UserAttributesRequest request) {
    return userMapper.toDto(
        userService.updateUserAttributes(
            id,
            request.rank(),
            request.description(),
            request.displayName(),
            request.version(),
            request.joinDate()));
  }

  /**
   * Body for {@link #updateUserAttributes}.
   *
   * @param rank the member's rank
   * @param description the profile description
   * @param displayName the display name
   * @param version the user row's expected version
   * @param joinDate the join date, or {@code null} to clear it
   */
  public record UserAttributesRequest(
      @jakarta.validation.constraints.NotNull Integer rank,
      @Size(max = 10_000) String description,
      @Size(max = 255) String displayName,
      @jakarta.validation.constraints.NotNull Long version,
      @Nullable LocalDate joinDate) {}

  /**
   * Body for {@link #updateMyDescription}.
   *
   * @param description the profile description
   * @param displayName the display name
   * @param version the user row's expected version
   */
  public record UserDescriptionRequest(
      @Size(max = 10_000) String description,
      @Size(max = 255) String displayName,
      @jakarta.validation.constraints.NotNull Long version) {}
}
