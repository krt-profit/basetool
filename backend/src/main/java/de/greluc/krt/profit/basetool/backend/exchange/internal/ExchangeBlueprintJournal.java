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

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCommands;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the journal state of a member's blueprint: what the blueprint writer records and what an
 * undo reads back to add or remove it again (REQ-XCH-022, ADR-0285).
 */
@Component
@RequiredArgsConstructor
public class ExchangeBlueprintJournal implements ExchangeEntryRestorer {

  private final PersonalBlueprintRepository blueprintRepository;
  private final PersonalBlueprintCommands blueprintService;
  private final ExchangeChangeRepository changeRepository;
  private final ObjectMapper objectMapper;

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.BLUEPRINT;
  }

  /**
   * Adds or removes the blueprint to match its state before the client's first write.
   *
   * @param scope the member and the client
   * @param group the client's writes to the blueprint, newest first
   * @return {@code null} when restored, {@code CHANGED_AFTERWARDS} when something else changed it,
   *     {@code GONE} when its earlier state names an acquisition date that cannot be read
   */
  @Override
  public @Nullable String restore(
      @NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group) {
    ExchangeJournalEntry newest = group.getFirst();
    String productKey = newest.getEntityKey();
    if (ExchangeEntryRestorer.changedAfterwards(
        changeRepository, scope.member(), newest, ExchangeBlueprintFeedService.keyOf(productKey))) {
      return ExchangeUndoService.CHANGED_AFTERWARDS;
    }
    String json = group.getLast().getBeforeState();
    if (json != null && State.unreadable(objectMapper.readTree(json))) {
      return ExchangeUndoService.GONE;
    }
    State before = read(json);
    Optional<PersonalBlueprint> owned =
        blueprintRepository.findByOwnerUserIdAndProductKey(scope.member(), productKey);
    if (before == null) {
      owned.ifPresent(blueprint -> blueprintService.delete(scope.member(), blueprint.getId()));
    } else if (owned.isEmpty()) {
      blueprintService.add(
          scope.member(),
          new PersonalBlueprintCreateRequest(productKey, before.acquiredAt(), before.note()));
    }
    return null;
  }

  /**
   * Reads a journaled blueprint state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} when the blueprint was absent
   */
  @Nullable
  State read(@Nullable String json) {
    return json == null ? null : State.read(objectMapper.readTree(json));
  }

  /**
   * What the journal holds of a blueprint.
   *
   * @param productKey the product
   * @param productName the product's name, or {@code null}
   * @param acquiredAt when the member acquired it, or {@code null}
   * @param note the member's note, or {@code null}
   * @param override whether the add that wrote it overrode another channel's removal
   */
  record State(
      @Nullable String productKey,
      @Nullable String productName,
      @Nullable Instant acquiredAt,
      @Nullable String note,
      boolean override) {

    /**
     * Captures what an undo needs to restore a blueprint.
     *
     * @param blueprint the blueprint
     * @return its state
     */
    static @NotNull State of(@NotNull PersonalBlueprint blueprint) {
      return new State(
          blueprint.getProductKey(),
          blueprint.getProductName(),
          blueprint.getAcquiredAt(),
          blueprint.getNote(),
          false);
    }

    /**
     * Marks the state as written by an add that overrode another channel's removal.
     *
     * @return the marked state
     */
    @NotNull
    State overriding() {
      return new State(productKey, productName, acquiredAt, note, true);
    }

    /**
     * Turns the state into the journal's JSON shape: {@code productKey} and {@code productName}
     * always, {@code acquiredAt}, {@code note} and {@code override} only when set.
     *
     * @return the state as the journal stores it
     */
    @NotNull
    Map<String, Object> toJournal() {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("productKey", productKey);
      state.put("productName", productName);
      if (acquiredAt != null) {
        state.put("acquiredAt", acquiredAt.toString());
      }
      if (note != null) {
        state.put("note", note);
      }
      if (override) {
        state.put("override", true);
      }
      return state;
    }

    /**
     * Whether a stored state names an acquisition date that is no instant.
     *
     * @param node the state
     * @return {@code true} when restoring it would lose the date
     */
    static boolean unreadable(@NotNull JsonNode node) {
      String acquiredAt = ExchangeJournalJson.text(node, "acquiredAt");
      return acquiredAt != null && instant(acquiredAt) == null;
    }

    /**
     * Reads a state the journal stores, from any version of the writer.
     *
     * @param node the state
     * @return the state; an unreadable acquisition date reads as none
     */
    static @NotNull State read(@NotNull JsonNode node) {
      String acquiredAt = ExchangeJournalJson.text(node, "acquiredAt");
      Instant acquired = acquiredAt == null ? null : instant(acquiredAt);
      JsonNode override = node.get("override");
      return new State(
          ExchangeJournalJson.text(node, "productKey"),
          ExchangeJournalJson.text(node, "productName"),
          acquired,
          ExchangeJournalJson.text(node, "note"),
          override != null && override.asBoolean());
    }

    /**
     * Parses an instant.
     *
     * @param raw the text
     * @return the instant, or {@code null} when the text is no instant
     */
    private static @Nullable Instant instant(@NotNull String raw) {
      try {
        return Instant.parse(raw);
      } catch (DateTimeParseException _) {
        return null;
      }
    }
  }
}
