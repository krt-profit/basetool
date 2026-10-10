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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.BlueprintSource;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintKeyService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a client's blueprint changes to the member's set: adds and removes, planned as a whole,
 * checked against the mass-change guard, and written through the web's own paths with every entry
 * journaled (REQ-XCH-014, REQ-XCH-015, REQ-XCH-021, REQ-XCH-022, ADR-0218).
 */
@Service
@RequiredArgsConstructor
public class ExchangeBlueprintWriteService {

  /** The result of an op whose state was already what it asked for. */
  static final String UNCHANGED = "unchanged";

  /** The result of an op whose reference matched no product. */
  static final String UNMATCHED = "unmatched";

  /** The result of an op whose reference matched several products. */
  static final String AMBIGUOUS = "ambiguous";

  /** The result of an op the server refused. */
  static final String REJECTED = "rejected";

  /** The outcome label of an applied op. */
  static final String APPLIED = "applied";

  /** The outcome label of a change set the guard held back. */
  static final String HELD = "held";

  /** The refusal of removing a default-granted blueprint. */
  static final String DEFAULT_NOT_REMOVABLE = "DEFAULT_NOT_REMOVABLE";

  /** The refusal of re-adding an entry removed by another channel or installation. */
  static final String REMOVED_ELSEWHERE = "REMOVED_ELSEWHERE";

  /** The reason of an op whose reference matched no product. */
  static final String UNMATCHED_REASON = "UNMATCHED";

  /** The reason of an op whose reference matched several products. */
  static final String AMBIGUOUS_REASON = "AMBIGUOUS";

  private static final String CLIENT_CHANNEL = "client";

  private final PersonalBlueprintRepository blueprintRepository;
  private final PersonalBlueprintService blueprintService;
  private final BlueprintProductService productService;
  private final DefaultBlueprintKeyService defaultKeys;
  private final ExchangeResolveService resolveService;
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeJournalService journalService;
  private final ExchangeMassChangeGuard guard;
  private final MeterRegistry meterRegistry;
  private final ExchangeLiveSync liveSync;

  /**
   * Plans and applies one change set in one transaction.
   *
   * @param caller the client, installation and member
   * @param changeSet the changes
   * @return the counts and the detail of every op that was not applied
   * @throws ExchangeProblemException {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} when the batch
   *     removes too much; nothing is written then
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeBlueprintChangeSet changeSet) {
    return run(caller, changeSet, true);
  }

  /**
   * Applies a change set the member confirmed after the mass-change guard held it back, without
   * asking the guard again (REQ-XCH-021).
   *
   * @param caller the client, installation and member the change set was staged for
   * @param changeSet the changes
   * @return the counts and the detail of every op that was not applied
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeBlueprintChangeSet changeSet) {
    return run(caller, changeSet, false);
  }

  /**
   * Plans a change set, asks the guard when told to, and applies it.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @param guarded whether the mass-change guard decides
   * @return the result
   */
  private @NotNull ExchangeChangeResultDto run(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeBlueprintChangeSet changeSet,
      boolean guarded) {
    List<Planned> plan = plan(caller, changeSet.ops());
    long removals = plan.stream().filter(p -> p instanceof Change change && !change.add()).count();
    if (guarded
        && !changeSet.dryRun()
        && guard.requiresConfirmation(
            caller,
            ExchangeResource.BLUEPRINT,
            blueprintRepository.countByOwnerUserId(caller.member()),
            removals)) {
      counter(caller, HELD).increment();
      throw ExchangeProblemException.massChangeConfirmationRequired();
    }
    UUID batch = UUID.randomUUID();
    int applied = 0;
    int unchanged = 0;
    List<ExchangeChangeResultDto.OpResult> results = new ArrayList<>();
    for (int i = 0; i < plan.size(); i++) {
      String opId = changeSet.ops().get(i).opId();
      if (plan.get(i) instanceof Change change) {
        if (!changeSet.dryRun()) {
          execute(caller, batch, change);
          counter(caller, APPLIED).increment();
        }
        applied++;
      } else if (plan.get(i) instanceof Skip skip) {
        if (UNCHANGED.equals(skip.result())) {
          unchanged++;
        }
        if (!changeSet.dryRun()) {
          counter(caller, skip.result()).increment();
        }
        results.add(new ExchangeChangeResultDto.OpResult(i, opId, skip.result(), skip.reason()));
      }
    }
    if (!changeSet.dryRun() && applied > 0) {
      liveSync.blueprintsChanged(caller.member());
    }
    return new ExchangeChangeResultDto(
        changeSet.dryRun(),
        applied,
        unchanged,
        plan.size() - applied - unchanged,
        results,
        null,
        null,
        null);
  }

  /**
   * Decides every op against the member's current set, in order, as if the earlier ops had run.
   *
   * @param caller the caller
   * @param ops the ops
   * @return one plan per op
   */
  private @NotNull List<Planned> plan(
      @NotNull ExchangeCaller caller, @NotNull List<ExchangeBlueprintChangeSet.Op> ops) {
    Map<Integer, ExchangeResolveResponse.Result> resolved = resolve(ops);
    Map<String, String> keysByBt = new HashMap<>();
    Map<String, Boolean> owned = new LinkedHashMap<>();
    List<Planned> plan = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      ExchangeBlueprintChangeSet.Op op = ops.get(i);
      String productKey;
      if (op.key() != null && "remove".equals(op.op())) {
        productKey = productKeyOf(op.key(), keysByBt);
      } else if (op.ref() != null) {
        ExchangeResolveResponse.Result result = resolved.get(i);
        if (result.status() == ExchangeResolveResponse.Status.UNMATCHED) {
          plan.add(new Skip(UNMATCHED, UNMATCHED_REASON));
          continue;
        }
        if (result.status() == ExchangeResolveResponse.Status.AMBIGUOUS) {
          plan.add(new Skip(AMBIGUOUS, AMBIGUOUS_REASON));
          continue;
        }
        productKey = productKeyOf(result.ref().bt(), keysByBt);
      } else {
        plan.add(new Skip(UNMATCHED, UNMATCHED_REASON));
        continue;
      }
      if (productKey == null) {
        plan.add(new Skip(UNMATCHED, UNMATCHED_REASON));
        continue;
      }
      boolean present =
          owned.computeIfAbsent(
              productKey,
              k -> blueprintRepository.existsByOwnerUserIdAndProductKey(caller.member(), k));
      if ("add".equals(op.op())) {
        if (present) {
          plan.add(new Skip(UNCHANGED, null));
        } else if (!Boolean.TRUE.equals(op.override()) && removedElsewhere(caller, productKey)) {
          plan.add(new Skip(REJECTED, REMOVED_ELSEWHERE));
        } else {
          owned.put(productKey, true);
          plan.add(new Change(true, productKey, op));
        }
      } else if (!present) {
        plan.add(new Skip(UNCHANGED, null));
      } else if (defaultKeys.isDefault(productKey)) {
        plan.add(new Skip(REJECTED, DEFAULT_NOT_REMOVABLE));
      } else {
        owned.put(productKey, false);
        plan.add(new Change(false, productKey, op));
      }
    }
    return plan;
  }

  /**
   * Resolves the references of the ops that carry one, in one call.
   *
   * @param ops the ops
   * @return the resolution by op index
   */
  private @NotNull Map<Integer, ExchangeResolveResponse.Result> resolve(
      @NotNull List<ExchangeBlueprintChangeSet.Op> ops) {
    List<Integer> indexes = new ArrayList<>();
    List<ExchangeItemRef> refs = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      ExchangeBlueprintChangeSet.Op op = ops.get(i);
      if (op.ref() != null && !(op.key() != null && "remove".equals(op.op()))) {
        indexes.add(i);
        refs.add(op.ref());
      }
    }
    Map<Integer, ExchangeResolveResponse.Result> byIndex = new HashMap<>();
    if (refs.isEmpty()) {
      return byIndex;
    }
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.BLUEPRINT, refs));
    for (ExchangeResolveResponse.Result result : response.results()) {
      byIndex.put(indexes.get(result.index()), result);
    }
    return byIndex;
  }

  /**
   * Turns a feed key or a resolved {@code bt} back into the product key.
   *
   * @param key the key, the product key itself or its {@code h:} hash
   * @param keysByBt the hashes already looked up
   * @return the product key, or {@code null} when no product has that hash
   */
  private @Nullable String productKeyOf(
      @NotNull String key, @NotNull Map<String, String> keysByBt) {
    if (!key.startsWith("h:")) {
      return key;
    }
    if (keysByBt.isEmpty()) {
      for (BlueprintProductService.ResolvedProduct product : productService.allProducts()) {
        keysByBt.put(
            ExchangeBlueprintFeedService.keyOf(product.productKey()), product.productKey());
      }
    }
    return keysByBt.get(key);
  }

  /**
   * Whether the product's latest change was its removal by another channel or installation, and so
   * leaves a live tombstone this client may not overwrite without asking the member.
   *
   * @param caller the caller
   * @param productKey the product, not in the member's set
   * @return {@code true} when a live tombstone of someone else stands
   */
  private boolean removedElsewhere(@NotNull ExchangeCaller caller, @NotNull String productKey) {
    Optional<ExchangeChange> latest =
        changeRepository.findLatestForKey(
            caller.member(), ExchangeResource.BLUEPRINT.name(), productKey);
    if (latest.isEmpty()) {
      return false;
    }
    ExchangeChange change = latest.get();
    boolean sameInstallation =
        CLIENT_CHANNEL.equals(change.getSourceChannel())
            && caller.clientId().equals(change.getSourceClient())
            && caller.installationKey().equals(change.getSourceKey());
    return !sameInstallation;
  }

  /**
   * Writes one planned change through the web's own path and journals it.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param planned the change
   */
  private void execute(
      @NotNull ExchangeCaller caller, @NotNull UUID batch, @NotNull Change planned) {
    if (planned.add()) {
      ExchangeBlueprintChangeSet.Provenance provenance = planned.op().provenance();
      blueprintService.add(
          caller.member(),
          new PersonalBlueprintCreateRequest(planned.productKey(), planned.op().acquiredAt(), null),
          BlueprintSource.fromClient(provenance == null ? null : provenance.source()),
          caller.clientId());
      PersonalBlueprint added =
          blueprintRepository
              .findByOwnerUserIdAndProductKey(caller.member(), planned.productKey())
              .orElseThrow();
      Map<String, Object> after = state(added);
      if (Boolean.TRUE.equals(planned.op().override())) {
        after.put("override", true);
      }
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.BLUEPRINT_ADD,
          planned.productKey(),
          false,
          null,
          after);
    } else {
      PersonalBlueprint removed =
          blueprintRepository
              .findByOwnerUserIdAndProductKey(caller.member(), planned.productKey())
              .orElseThrow();
      Map<String, Object> before = state(removed);
      blueprintService.delete(caller.member(), removed.getId());
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.BLUEPRINT_REMOVE,
          planned.productKey(),
          true,
          before,
          null);
    }
  }

  /**
   * Captures what an undo needs to restore a blueprint.
   *
   * @param blueprint the blueprint
   * @return its product, name, acquisition date and note
   */
  private static @NotNull Map<String, Object> state(@NotNull PersonalBlueprint blueprint) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("productKey", blueprint.getProductKey());
    state.put("productName", blueprint.getProductName());
    Instant acquiredAt = blueprint.getAcquiredAt();
    if (acquiredAt != null) {
      state.put("acquiredAt", acquiredAt.toString());
    }
    if (blueprint.getNote() != null) {
      state.put("note", blueprint.getNote());
    }
    return state;
  }

  /**
   * Returns the write counter of the caller's client and one outcome.
   *
   * @param caller the caller, whose client the relay bounded by the registry
   * @param outcome the outcome
   * @return the counter
   */
  private io.micrometer.core.instrument.Counter counter(
      @NotNull ExchangeCaller caller, @NotNull String outcome) {
    return meterRegistry.counter(
        MetricNames.EXCHANGE_WRITES,
        MetricNames.TAG_CLIENT_ID,
        caller.clientId(),
        MetricNames.TAG_RESOURCE,
        ExchangeResource.BLUEPRINT.name().toLowerCase(java.util.Locale.ROOT),
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /** What one op of a change set will do. */
  private sealed interface Planned permits Change, Skip {}

  /**
   * An op that changes the member's set.
   *
   * @param add {@code true} for an add, {@code false} for a removal
   * @param productKey the product it acts on
   * @param op the op
   */
  private record Change(
      boolean add, @NotNull String productKey, @NotNull ExchangeBlueprintChangeSet.Op op)
      implements Planned {}

  /**
   * An op that changes nothing.
   *
   * @param result its result
   * @param reason its refusal code, or {@code null}
   */
  private record Skip(@NotNull String result, @Nullable String reason) implements Planned {}
}
