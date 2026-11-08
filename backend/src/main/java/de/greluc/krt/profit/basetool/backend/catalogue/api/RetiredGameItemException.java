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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.io.Serial;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a write would set or keep a game item the P4K retired on an entry (REQ-P4K-013).
 *
 * <p>Answers {@code 400} with code {@code CATALOGUE_ITEM_RETIRED} and the extension property {@code
 * gameItemId}; nothing is written.
 */
@Getter
public final class RetiredGameItemException extends DomainProblem {

  @Serial private static final long serialVersionUID = 1L;

  /** The bundle key the localised {@code detail} resolves from. */
  private static final String DETAIL_KEY = "problem.catalogue_item_retired.detail";

  /** The retired item the write named. */
  @NotNull private final UUID gameItemId;

  /**
   * Creates the exception; its {@code detail} is the localised text of {@link #DETAIL_KEY}.
   *
   * @param gameItemId the retired item the write named
   */
  public RetiredGameItemException(@NotNull UUID gameItemId) {
    super(DETAIL_KEY);
    this.gameItemId = gameItemId;
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return CatalogueProblemCode.CATALOGUE_ITEM_RETIRED.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code CATALOGUE_ITEM_RETIRED}
   */
  @NotNull
  @Override
  public String code() {
    return CatalogueProblemCode.CATALOGUE_ITEM_RETIRED.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Retired catalogue item"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Retired catalogue item";
  }

  /**
   * Adds the retired item's id to the problem body.
   *
   * @return {@code gameItemId}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, Object> extraProperties() {
    return Map.of("gameItemId", gameItemId.toString());
  }
}
