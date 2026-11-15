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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroup;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One patch change, recorded once by an admin for every org unit (REQ-PATCH-001): its label, kind,
 * live time, options, the admin who triggered it and what its effects touched.
 *
 * <p>The id is assigned before the trigger's transaction begins, so the exchange feed can name the
 * patch change on every row the trigger writes (REQ-PATCH-007). Only the label changes afterwards.
 */
@Entity
@Table(name = "patch_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchChange extends AbstractEntity<UUID> {

  /** The id, assigned by the trigger. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  private UUID id;

  /** The patch label, a validated version such as {@code 4.4} or {@code 4.4.1}. */
  @Column(name = "label", nullable = false, length = 32)
  private String label;

  /** What the patch kept. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private PatchKind kind;

  /** When the patch went live, the cutoff of every effect; fixed once triggered. */
  @Column(name = "live_at", nullable = false, updatable = false)
  private Instant liveAt;

  /** When the admin triggered it. */
  @Column(name = "triggered_at", nullable = false, updatable = false)
  private Instant triggeredAt;

  /** The admin who triggered it, or {@code null} when the trigger ran without a member. */
  @Column(name = "triggered_by", updatable = false)
  @Nullable
  private UUID triggeredBy;

  /** The selected wipe groups. */
  @ElementCollection
  @CollectionTable(
      name = "patch_change_wipe_group",
      joinColumns = @JoinColumn(name = "patch_change_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "wipe_group", nullable = false, length = 32)
  @Getter(AccessLevel.NONE)
  private Set<WipeGroup> wipeGroups = EnumSet.noneOf(WipeGroup.class);

  /** The single game items and materials kept or wiped beside the groups. */
  @ElementCollection
  @CollectionTable(
      name = "patch_change_wipe_exception",
      joinColumns = @JoinColumn(name = "patch_change_id"))
  @Getter(AccessLevel.NONE)
  private Set<WipeException> wipeExceptions = new HashSet<>();

  /** Whether running refinery orders started before the live time end. */
  @Column(name = "cancel_refinery_orders", nullable = false, updatable = false)
  private boolean cancelRefineryOrders;

  /** Whether the currency wipe runs. */
  @Column(name = "currency_wipe", nullable = false, updatable = false)
  private boolean currencyWipe;

  /** Whether members are asked for a new home location. */
  @Column(name = "home_location_prompt", nullable = false, updatable = false)
  private boolean homeLocationPrompt;

  /**
   * When an admin ended the home-location phase early, or {@code null}; written only by {@link
   * PatchChangeRepository#endPhase}, so a label correction never touches it (REQ-PATCH-024).
   */
  @Column(name = "phase_ended_at", insertable = false, updatable = false)
  @Nullable
  private Instant phaseEndedAt;

  /** The admin who ended the home-location phase, or {@code null}; written with the end. */
  @Column(name = "phase_ended_by", insertable = false, updatable = false)
  @Nullable
  private UUID phaseEndedBy;

  /** What the effects touched at the trigger, one count per kind. */
  @ElementCollection
  @CollectionTable(
      name = "patch_change_effect_count",
      joinColumns = @JoinColumn(name = "patch_change_id"))
  @MapKeyEnumerated(EnumType.STRING)
  @MapKeyColumn(name = "count_kind", length = 48)
  @Column(name = "count_value", nullable = false)
  @Getter(AccessLevel.NONE)
  private Map<PatchCount, Long> effectCounts = new EnumMap<>(PatchCount.class);

  /**
   * Records a patch change at its trigger.
   *
   * @param id the id assigned before the trigger's transaction began
   * @param label the validated label
   * @param kind the patch kind
   * @param plan the live time and options
   * @param triggeredAt when the admin triggered it
   * @param triggeredBy the triggering admin, or {@code null} when no member is known
   */
  PatchChange(
      @NotNull UUID id,
      @NotNull PatchLabel label,
      @NotNull PatchKind kind,
      @NotNull PatchChangePlan plan,
      @NotNull Instant triggeredAt,
      @Nullable UUID triggeredBy) {
    this.id = id;
    this.label = label.value();
    this.kind = kind;
    this.liveAt = plan.liveAt();
    this.triggeredAt = triggeredAt;
    this.triggeredBy = triggeredBy;
    PatchChangeOptions options = plan.options();
    this.wipeGroups =
        options.wipeGroups().isEmpty()
            ? EnumSet.noneOf(WipeGroup.class)
            : EnumSet.copyOf(options.wipeGroups());
    this.wipeExceptions = new HashSet<>(options.wipeExceptions());
    this.cancelRefineryOrders = options.cancelRefineryOrders();
    this.currencyWipe = options.currencyWipe();
    this.homeLocationPrompt = options.homeLocationPrompt();
  }

  /**
   * A patch change is new until its first flush gives it a version; its id is assigned, not
   * generated.
   *
   * @return {@code true} while the record has not been persisted
   */
  @Override
  public boolean isNew() {
    return getVersion() == null;
  }

  /**
   * Returns the options the patch change was triggered with.
   *
   * @return the options
   */
  public @NotNull PatchChangeOptions options() {
    return new PatchChangeOptions(
        wipeGroups, wipeExceptions, cancelRefineryOrders, currencyWipe, homeLocationPrompt);
  }

  /**
   * Returns the label as a comparable version.
   *
   * @return the label
   */
  public @NotNull PatchLabel patchLabel() {
    return new PatchLabel(label);
  }

  /**
   * Returns what the effects touched at the trigger.
   *
   * @return the counts
   */
  public @NotNull PatchCounts counts() {
    return PatchCounts.copyOf(effectCounts);
  }

  /**
   * Stores what the effects touched at the trigger.
   *
   * @param counts the merged counts of every effect
   */
  void recordCounts(@NotNull PatchCounts counts) {
    effectCounts.clear();
    effectCounts.putAll(counts.asMap());
  }

  /**
   * Corrects the label; the live time and the effects stay as they ran (ADR-0381).
   *
   * @param corrected the validated new label
   */
  void correctLabel(@NotNull PatchLabel corrected) {
    this.label = corrected.value();
  }
}
