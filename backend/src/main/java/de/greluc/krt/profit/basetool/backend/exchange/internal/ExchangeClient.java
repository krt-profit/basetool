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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An approved third-party exchange client in the registry (REQ-XCH-003, ADR-0217); the database
 * alone decides its status and capabilities.
 */
@Entity
@Table(name = "exchange_client")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ExchangeClient extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The Keycloak client id; lower-case letters, digits and hyphens, immutable after creation. */
  @Column(name = "client_id", nullable = false, updatable = false, length = 63)
  private String clientId;

  /** The product name shown to members and in the audit viewer. */
  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  /** Whether the client may use the exchange at all. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ExchangeClientStatus status;

  /** The oldest client release the gateway still serves, or {@code null} for no floor. */
  @Nullable
  @Column(name = "min_client_version", length = 32)
  private String minClientVersion;

  /** Where members find the client's privacy statement and security contact. */
  @Nullable
  @Column(name = "contact_url", length = 500)
  private String contactUrl;

  /** The per-minute request limit overriding the gateway default, or {@code null}. */
  @Nullable
  @Column(name = "requests_per_minute")
  private Integer requestsPerMinute;

  /** The daily write quota overriding the gateway default, or {@code null}. */
  @Nullable
  @Column(name = "writes_per_day")
  private Integer writesPerDay;

  /** The capabilities granted to the client. */
  @NotNull
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "exchange_client_capability",
      joinColumns = @JoinColumn(name = "exchange_client_id"))
  @Column(name = "capability", nullable = false, length = 40)
  @Convert(converter = ExchangeCapabilityConverter.class)
  @ToString.Exclude
  private Set<ExchangeCapability> capabilities = new HashSet<>();
}
