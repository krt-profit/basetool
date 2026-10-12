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

package de.greluc.krt.profit.basetool.backend.privacy.web;

import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.privacy.api.AccountConsolidationService;
import de.greluc.krt.profit.basetool.backend.privacy.api.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints that remove a member account: the hard delete with all owned data and the
 * consolidation of an approved duplicate into the account the member keeps (REQ-SEC-055). Both call
 * the GDPR services of the privacy module.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class AccountRemovalController {

  private final UserDeletionService userDeletionService;
  private final UserMapper userMapper;

  /** The consolidate action's orchestrator (REQ-SEC-055): duplicate in, survivor out. */
  private final AccountConsolidationService accountConsolidationService;

  /**
   * ADMIN-only: deletes a user account along with all owned data (ships, inventory, refinery
   * orders, mission memberships).
   *
   * @param id user id
   */
  @DeleteMapping("/users/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteUser(@PathVariable @NotNull UUID id) {
    userDeletionService.deleteUser(id);
  }

  /**
   * Folds an approved duplicate account into the account the member keeps (REQ-SEC-055).
   *
   * <p>Owned data moves to the survivor, the Discord identity is re-linked in Keycloak, and the
   * duplicate's row and Keycloak user are removed. The path names the duplicate, the body the
   * survivor. ADMIN-only.
   *
   * @param id the duplicate account to dissolve
   * @param adminUserId the acting admin, recorded as the audit actor
   * @param body the surviving account's id and the duplicate's optimistic-lock version
   * @return the surviving account, carrying the moved Discord link
   */
  @PostMapping("/users/{id}/consolidate")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public UserDto consolidateAccount(
      @PathVariable @NotNull UUID id,
      @CurrentUserId UUID adminUserId,
      @NotNull @RequestBody @Valid ConsolidateAccountRequest body) {
    return userMapper.toDto(
        accountConsolidationService.consolidate(
            id, body.targetUserId(), body.version(), adminUserId));
  }
}
