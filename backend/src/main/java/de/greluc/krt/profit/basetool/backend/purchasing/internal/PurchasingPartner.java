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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * A counterparty of a deal (REQ-PUR-004): an organisation or, as an in-game handle only, an
 * individual. History is computed from the deals and never stored here.
 */
@Entity
@Table(name = "purchasing_partner")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingPartner extends AbstractEntity<UUID> {

  /** The name an erased individual carries. */
  public static final String ERASED_NAME = "—";

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PartnerKind kind;

  /** The organisation's name or the individual's in-game handle. */
  @Column(nullable = false, length = 120)
  private String name;

  /** The organisation's tag, or {@code null}. */
  @Nullable
  @Column(name = "org_tag", length = 16)
  private String orgTag;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PartnerChannel channel;

  @Nullable
  @Column(name = "conditions_note", length = 500)
  private String conditionsNote;

  @Nullable
  @Column(name = "reputation_note", length = 500)
  private String reputationNote;

  @Enumerated(EnumType.STRING)
  @Column(name = "contact_via", nullable = false, length = 16)
  private PartnerContact contactVia;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PartnerStatus status = PartnerStatus.ACTIVE;

  /** Whether an individual's handle and notes were erased on request (REQ-PUR-006). */
  @Column(nullable = false)
  private boolean erased;

  @Nullable
  @Column(name = "created_by")
  private UUID createdBy;
}
