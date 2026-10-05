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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operation module's access policy: the read, ledger and edit gates of the {@code Operation}
 * aggregate, evaluated on the scope kernel (plan §5.4, ADR-0236).
 *
 * <p>Invoked from SpEL as {@code @operationAccessPolicy}. Unknown ids are refused. Read-only
 * transactional.
 */
@Service(OperationAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "operationAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;
  private final OperationRepository operationRepository;

  /**
   * Checks whether the caller may read operation {@code operationId}: when the owning-org-unit
   * check passes, when it is ownerless and the caller is member-or-above (REQ-ORG-009), or when the
   * caller participated in one of its linked missions.
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller may read the operation
   */
  public boolean canSeeOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o -> {
              boolean scopeVisible =
                  o.getOwningOrgUnit() == null
                      ? authHelper.isMemberOrAbove()
                      : ownerScopeService.canSeeSquadron(o.getOwningOrgUnit().getId());
              return scopeVisible || participatedInOperation(operationId);
            })
        .orElse(false);
  }

  /**
   * {@link #canSeeOperation(UUID)} without the participant escape, for the finance endpoints:
   * mission participation is self-issuable and so does not unlock a foreign ledger.
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller reaches the operation without the participant escape
   */
  public boolean canSeeOperationLedger(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o ->
                o.getOwningOrgUnit() == null
                    ? authHelper.isMemberOrAbove()
                    : ownerScopeService.canSeeSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit operation {@code operationId}: strict owning-org-unit check;
   * an ownerless operation passes and is restricted by the controller's role gate (REQ-ORG-009).
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the operation
   */
  public boolean canEditOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o ->
                o.getOwningOrgUnit() == null
                    || ownerScopeService.canEditSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  private boolean participatedInOperation(@NotNull UUID operationId) {
    return authHelper
        .currentUserId()
        .map(uid -> operationRepository.existsParticipantUserInOperation(operationId, uid))
        .orElse(false);
  }
}
