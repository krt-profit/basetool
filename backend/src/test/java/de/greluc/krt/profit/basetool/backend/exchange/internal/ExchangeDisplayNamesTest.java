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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the display-name rules of the exchange registry (REQ-XCH-003). */
class ExchangeDisplayNamesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "VerseKit",
        "SC Trade Tools 2",
        "Café Sync",
        "Hangar-Link (beta)",
        "Mining & More: v2.0!",
        "Erkul's Helper",
        "Tool/Kit_3+"
      })
  void anOrdinaryNamePasses(String name) {
    assertThat(ExchangeDisplayNames.violation(name)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Verse‮Kit",
        "Verse​Kit",
        "Verse⁦Kit⁩",
        "Verse­Kit",
        "ВerseKit",
        "ＶerseKit",
        "VerseKit 🚀",
        "Verse\tKit",
        "-VerseKit",
        "Verse<Kit>",
        "Verse Kit"
      })
  void aControlFormatForeignScriptOrOddCharacterIsRefused(String name) {
    assertThat(ExchangeDisplayNames.violation(name)).contains(ExchangeDisplayNames.INVALID);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Basetool",
        "Profit Basetool",
        "basetool sync",
        "Base-Tool",
        "BASE TOOL",
        "Bäsetool Companion",
        "My.Base.Tool"
      })
  void aNamePosingAsTheBasetoolIsRefused(String name) {
    assertThat(ExchangeDisplayNames.violation(name)).contains(ExchangeDisplayNames.RESERVED);
  }
}
