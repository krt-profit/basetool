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
 * One user's acceptance of one Terms of Use version, the evidence that enforcement of a clause
 * rests on (REQ-SEC-028).
 *
 * <p>Append-only: re-consent after a terms change inserts a new row, so the entity has no
 * {@code @Version}; a unique constraint on user and version makes a duplicate submit a no-op.
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
   * The accepted terms version, a content digest of the wording generated at build time by the
   * {@code generateTermsVersion} Gradle task.
   */
  @Column(name = "terms_version", nullable = false, length = 64)
  private String termsVersion;

  /** When the user accepted, in UTC (REQ-API-006). */
  @Column(name = "accepted_at", nullable = false)
  private Instant acceptedAt;
}
