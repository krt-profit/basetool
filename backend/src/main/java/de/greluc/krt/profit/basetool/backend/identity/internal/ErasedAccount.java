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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One entry of the erasure register (REQ-DATA-048): the former id of a deleted account and when it
 * was deleted. Written only through {@link ErasedAccountRepository#insertIfAbsent}.
 */
@Entity
@Table(name = "erased_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ErasedAccount {

  /** The deleted account's former {@code app_user.id}, the Keycloak subject. */
  @Id private UUID id;

  /** When the account was deleted, in UTC (REQ-API-006). */
  @Column(name = "erased_at", nullable = false)
  private Instant erasedAt;
}
