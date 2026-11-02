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

package de.greluc.krt.profit.basetool.backend.bank.web;

import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceSearchService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member searches for the bank grantee and holder pickers: the same query, scope and projection as
 * the member searches, with the role gate widened to {@code BANK_EMPLOYEE} (REQ-BANK-008/009/044).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class BankMemberSearchController {

  private final UserDirectoryService userDirectoryService;
  private final UserReferenceSearchService userReferenceSearchService;

  /**
   * Bank-audience twin of the member search {@code GET /users/search} for the bank grantee/holder
   * pickers: identical query, scope and projection, with the role gate widened to {@code
   * BANK_EMPLOYEE} (REQ-BANK-008/009/044).
   *
   * @return paged user DTOs, peer-redacted for non-elevated callers
   */
  @GetMapping("/bank/members/search")
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.KRT_MEMBER
          + "', '"
          + Roles.BANK_EMPLOYEE
          + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserDto> searchUsersForBank(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userDirectoryService.search(query, page, size, sort);
  }

  /**
   * Bank-audience twin of the reference search {@code GET /users/search/references}, with the
   * widened role gate of {@link #searchUsersForBank}, for the {@code remote-bank-users} pickers
   * (REQ-BANK-008/009/044).
   *
   * @param query free-text username/displayName filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression ({@code username}, {@code rank} or {@code id}), or {@code
   *     null} for the username default
   * @return one page of matching user references
   */
  @GetMapping("/bank/members/search/references")
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.KRT_MEMBER
          + "', '"
          + Roles.BANK_EMPLOYEE
          + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserReferenceDto> searchUserReferencesForBank(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userReferenceSearchService.search(query, page, size, sort);
  }
}
