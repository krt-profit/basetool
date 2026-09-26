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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Curated mapping from an external catalogue's commodity name onto a local {@link Material}, used
 * by the syncs after a direct UUID match fails.
 *
 * <p>{@code (source_system, LOWER(external_name))} is unique via a functional DB index, so a
 * duplicate (including a case-only variant) is rejected with HTTP 409 (REQ-REFINERY-010).
 */
@Entity
@Table(name = "material_external_alias")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class MaterialExternalAlias extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The local material this alias points at. Cannot be {@code null} — the FK enforces this. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "material_id", nullable = false)
  private Material material;

  /** External catalogue the alias belongs to ({@code UEX} or {@code SCWIKI}). */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_system", nullable = false, length = 32)
  private MaterialExternalAliasSource sourceSystem;

  /** Commodity name as it appears in the external catalogue (e.g. Wiki's {@code "Raw Silicon"}). */
  @Column(name = "external_name", nullable = false)
  private String externalName;

  /**
   * Optional external internal key (e.g. SC Wiki's {@code "Agricium"} key field). Audit aid; the
   * resolution chain does not consult it.
   */
  @Column(name = "external_key")
  private String externalKey;

  /**
   * Optional external UUID. SC Wiki carries one for every commodity; UEX exposes integer ids
   * instead and leaves this {@code null} for UEX-side aliases.
   */
  @Column(name = "external_uuid")
  private UUID externalUuid;

  /**
   * Optional external short code (e.g. UEX's 4-letter trading codes like {@code "AGRI"}). Audit aid
   * only; uniqueness stays on {@code (source_system, LOWER(external_name))}.
   */
  @Column(name = "external_code", length = 64)
  private String externalCode;

  /** Free-form note explaining the alias provenance (verification source, in-game observation). */
  @Column(columnDefinition = "TEXT")
  private String note;

  /**
   * Identifier of the alias creator: {@code "system"} for V108 seed rows, the JWT {@code sub} for
   * admin-created rows.
   */
  @Column(name = "created_by")
  private String createdBy;
}
