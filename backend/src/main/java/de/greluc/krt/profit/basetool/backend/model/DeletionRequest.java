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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * A member's request to have their account erased (Art. 17 GDPR, REQ-SEC-061).
 *
 * <p>The member raises it on their profile page; an admin decides it. It is <b>never</b> carried
 * out on the spot, because the deletion removes the Keycloak account and purges warehouse stock and
 * a hangar while reassigning missions and refinery orders (REQ-DATA-008) — none of it reversible,
 * and a mis-click on one's own profile must not be able to start it (ADR-0181).
 *
 * <p>The row cascades away with the {@code app_user} row when the request is carried out, so a
 * decided-and-executed request is never readable afterwards. That is deliberate: the request is
 * itself personal data about the member, and the record of the deletion is the {@code USER_DELETED}
 * audit event.
 */
@Entity
@Table(name = "deletion_request")
@Getter
@Setter
@NoArgsConstructor
public class DeletionRequest extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The member asking to be erased. FK to {@code app_user(id)}, {@code ON DELETE CASCADE}. */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /**
   * Where the request stands. See {@link DeletionRequestStatus} for why there is no executed one.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DeletionRequestStatus status = DeletionRequestStatus.PENDING;

  /**
   * The member also asked for the handle snapshots that survive a deletion to be anonymised — both
   * audit trails, the bank booking history, the booking requests and the two handover recipients.
   *
   * <p><b>A wish, not an instruction.</b> Nothing acts on this automatically: an admin weighs it
   * against the legitimate interest in an auditable ledger and decides it deliberately (decision 6,
   *
   * @greluc; the procedure is in {@code docs/privacy/data-subject-requests.md}).
   */
  @Column(name = "erase_history_requested", nullable = false)
  private boolean eraseHistoryRequested;

  /** The admin who decided it, or {@code null} while it is pending. */
  @Nullable
  @Column(name = "decided_by_id")
  private UUID decidedById;

  /** When it was decided or withdrawn, or {@code null} while it is pending. */
  @Nullable
  @Column(name = "decided_at")
  private Instant decidedAt;

  /**
   * The admin's recorded reasoning. Mandatory — and DB-enforced — on {@link
   * DeletionRequestStatus#DECLINED}, because Art. 12(4) requires telling the requester why a
   * request is refused.
   */
  @Nullable
  @Column(name = "decision_note", columnDefinition = "TEXT")
  private String decisionNote;

  /**
   * Creates a pending request.
   *
   * @param userId the member asking to be erased
   * @param eraseHistoryRequested whether they also asked for the surviving handle snapshots to be
   *     anonymised
   */
  public DeletionRequest(UUID userId, boolean eraseHistoryRequested) {
    this.userId = userId;
    this.eraseHistoryRequested = eraseHistoryRequested;
    this.status = DeletionRequestStatus.PENDING;
  }
}
