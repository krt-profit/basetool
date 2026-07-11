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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.service.BankGrantService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for per-account grant management (epic #556, REQ-BANK-009): the flag matrix reads
 * and the create/patch/revoke mutations. Management-only as a whole (admins pass via the role
 * hierarchy); org-unit membership of the grantee is irrelevant in both directions (REQ-BANK-008).
 */
@RestController
@RequestMapping("/api/v1/bank/grants")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_BANK_MANAGEMENT)
public class BankGrantController {

  private final BankGrantService bankGrantService;

  /**
   * Lists grants for the matrix UI — optionally filtered per account (G1) or per employee (G2).
   *
   * @param accountId filter on one account, or absent
   * @param userId filter on one grantee, or absent
   * @return the matching grant rows incl. the inert marker
   */
  @Operation(summary = "List bank grants (management; filterable per account or employee)")
  @GetMapping
  @Transactional(readOnly = true)
  public List<BankGrantDto> getGrants(
      @RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID userId) {
    return bankGrantService.getGrants(accountId, userId);
  }

  /**
   * Creates a grant; the grantee must currently hold the Bank Employee role (REQ-BANK-009).
   *
   * @param request validated creation payload
   * @return the created grant row
   */
  @Operation(summary = "Create a bank grant (management)")
  @PostMapping
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public BankGrantDto createGrant(@RequestBody @Valid CreateBankGrantRequest request) {
    return bankGrantService.createGrant(request);
  }

  /**
   * Changes a grant's capability flags (REQ-BANK-009).
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   * @param request the new flags plus echoed version
   * @return the updated grant row
   */
  @Operation(summary = "Update a bank grant's capability flags (management)")
  @PatchMapping("/{userId}/{accountId}")
  @Transactional
  public BankGrantDto updateGrant(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID accountId,
      @RequestBody @Valid UpdateBankGrantRequest request) {
    return bankGrantService.updateGrant(userId, accountId, request);
  }

  /**
   * Revokes a grant — the grantee loses view access to the account (REQ-BANK-009).
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   */
  @Operation(summary = "Revoke a bank grant (management)")
  @DeleteMapping("/{userId}/{accountId}")
  @Transactional
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGrant(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID accountId) {
    bankGrantService.deleteGrant(userId, accountId);
  }
}
