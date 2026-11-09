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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What an actor may do as bank staff and as an approver, answered by {@link
 * BankAccessPolicy#staffRights} from the actor's roles, seats and grants alone; org-unit scope is
 * never consulted (REQ-BANK-008).
 *
 * @param actorId the actor's member id, or {@code null} for {@link BankActor#NOBODY}
 * @param staff whether the actor reaches {@code BANK_EMPLOYEE}
 * @param management whether the actor reaches {@code BANK_MANAGEMENT}, which sees and books on
 *     every account (REQ-BANK-010)
 * @param admin whether the actor reaches {@code ADMIN}, who approves any request and is not bound
 *     by four-eyes (REQ-BANK-062)
 * @param olMember whether the actor holds an Organisationsleitung seat, the top-band KRT approver
 *     (REQ-BANK-047)
 * @param grants the actor's grant rows by account id
 */
public record BankStaffRights(
    @Nullable UUID actorId,
    boolean staff,
    boolean management,
    boolean admin,
    boolean olMember,
    @NotNull @Unmodifiable Map<UUID, BankActor.Grant> grants) {

  /**
   * Copies the grants.
   *
   * @param actorId the actor's member id, or {@code null}
   * @param staff whether the actor is bank staff
   * @param management whether the actor is Bankleitung
   * @param admin whether the actor is an administrator
   * @param olMember whether the actor holds an OL seat
   * @param grants the grant rows by account id
   */
  public BankStaffRights {
    grants = Map.copyOf(grants);
  }

  /**
   * Whether the actor sees the account on the bank-staff side: management, or staff with any grant
   * row on it (REQ-BANK-009). The account need not exist.
   *
   * @param accountId the account
   * @return {@code true} iff the actor may read it as staff
   */
  public boolean canSee(@NotNull UUID accountId) {
    return staff && (management || grants.containsKey(accountId));
  }

  /**
   * Whether the actor may book or confirm a movement of this kind on the account: management, or
   * staff whose grant carries the matching flag (REQ-BANK-009, REQ-BANK-011, REQ-BANK-023).
   *
   * @param type the movement kind
   * @param accountId the (source) account
   * @return {@code true} iff the actor holds the capability
   */
  public boolean canBook(@NotNull BankBookingRequestType type, @NotNull UUID accountId) {
    if (!staff) {
      return false;
    }
    if (management) {
      return true;
    }
    BankActor.Grant grant = grants.get(accountId);
    return grant != null && grant.allows(type);
  }

  /**
   * The accounts whose requests the staff queue shows a non-management actor (REQ-BANK-023).
   *
   * @return the ids of the actor's grant rows
   */
  @NotNull
  @Unmodifiable
  public Set<UUID> grantedAccountIds() {
    return grants.keySet();
  }

  /**
   * Whether the four-eyes rule bars the actor from approving or confirming a request: the actor
   * raised it and is no administrator (REQ-BANK-062).
   *
   * @param requestedBy the request's requester, or {@code null}
   * @return {@code true} iff a second person must decide
   */
  public boolean boundByFourEyes(@Nullable UUID requestedBy) {
    return !admin && actorId != null && actorId.equals(requestedBy);
  }

  /**
   * Whether the actor may grant or revoke the approval of a request, and so sees it among the
   * foreign requests (REQ-BANK-041, REQ-BANK-046): an administrator always, a KRT band request its
   * band approver, any other request the account's responsible holder.
   *
   * @param approver the approver class the request is routed to, or {@code null}
   * @param responsibleHolder whether the actor is the responsible holder of the request's account
   * @return {@code true} iff the actor acts on the request's approval
   */
  public boolean mayApprove(@Nullable BankRequestApprover approver, boolean responsibleHolder) {
    if (admin) {
      return true;
    }
    if (approver == null) {
      return responsibleHolder;
    }
    return switch (approver) {
      case RESPONSIBLE_HOLDER -> responsibleHolder;
      case BANK_MANAGEMENT -> management;
      case ORGANISATIONSLEITUNG -> olMember;
    };
  }

  /**
   * Whether the actor may read a holder's custody history: management any holder, other staff only
   * their own (REQ-BANK-032).
   *
   * @param holderUserId the member the holder is registered for, or {@code null}
   * @return {@code true} iff the actor may read the holder
   */
  public boolean maySeeHolder(@Nullable UUID holderUserId) {
    return staff && (management || (actorId != null && actorId.equals(holderUserId)));
  }
}
