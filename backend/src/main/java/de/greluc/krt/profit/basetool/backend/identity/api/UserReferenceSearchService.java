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

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The squadron-scoped username/displayName search that returns the slim {@link UserReferenceDto}
 * rows of the {@code remote-users} and {@code remote-bank-users} comboboxes.
 *
 * <p>Read-only; it joins the calling transaction. No peer redaction applies, because the projection
 * already is the peer field set.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserReferenceSearchService {

  /**
   * Sort whitelist of the reference searches: the projected columns only ({@code email} is not part
   * of the reference projection, so it is not offered).
   */
  private static final Set<String> REFERENCE_SORT = Set.of("username", "rank", "id");

  private final UserService userService;

  /**
   * Resolves the page request against the sort whitelist and runs the squadron-scoped projection; a
   * {@code null} query (the browse-mode empty {@code ?query=}) matches all.
   *
   * @param query free-text username/displayName filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression ({@code username}, {@code rank} or {@code id}), or {@code
   *     null} for the username default
   * @return one page of matching user references
   */
  public @NotNull PageResponse<UserReferenceDto> search(
      @Nullable String query,
      @Nullable Integer page,
      @Nullable Integer size,
      @Nullable String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, REFERENCE_SORT, "username");
    return PageResponse.of(
        userService.searchReferencesByUsername(query == null ? "" : query, pageable));
  }
}
