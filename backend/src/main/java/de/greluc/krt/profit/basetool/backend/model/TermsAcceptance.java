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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One user's acceptance of one version of the Terms of Use (REQ-SEC-028, V229).
 *
 * <p>The row is the evidence that a specific person agreed to a specific wording at a specific
 * time, which is what makes enforcing a clause against them — for instance the section 4 obligation
 * to use only operator-approved client software (REQ-SEC-027) — rest on something more than
 * "continued use counts as consent".
 *
 * <p><strong>Append-only.</strong> A row is inserted when the user accepts and is never updated
 * afterwards; re-consent after a terms change writes a <em>new</em> row so the history survives.
 * That is why the entity carries no {@code @Version} — there is no second writer to race with, and
 * an optimistic-lock field on an insert-only table would only be misleading. The {@code
 * uq_terms_acceptance_user_version} constraint makes a duplicate submit a no-op at the database
 * level rather than a second history entry.
 */
@Entity
@Table(name = "terms_acceptance")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TermsAcceptance {

  /**
   * Surrogate key, assigned by the service rather than the database so the insert can be retried
   * idempotently and the value is known before the flush.
   */
  @Id private UUID id;

  /**
   * The accepting user, holding {@code app_user.id} — which is the Keycloak {@code sub}. Modelled
   * as the raw identifier instead of a {@code @ManyToOne} association on purpose: the per-request
   * acceptance check runs in a servlet filter that has the {@code sub} from the token and no reason
   * to materialise a {@link User} aggregate just to answer a boolean.
   */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /**
   * The terms version that was accepted — a content digest of the wording, derived at build time by
   * the root Gradle task {@code generateTermsVersion}. Storing the version rather than a boolean is
   * what lets a later wording change re-prompt without erasing the earlier consent.
   */
  @Column(name = "terms_version", nullable = false, length = 64)
  private String termsVersion;

  /** When the user accepted, in UTC (REQ-API-006). */
  @Column(name = "accepted_at", nullable = false)
  private Instant acceptedAt;
}
