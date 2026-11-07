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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Covers the P4K → Wiki → UEX precedence of a game item's canonical name (REQ-P4K-017). */
class GameItemNamePrecedenceTest {

  private static GameItem item(String name) {
    GameItem item = new GameItem();
    item.setName(name);
    return item;
  }

  @Test
  void theP4kNameReplacesAStaleNameOnlyWhenApplying() {
    GameItem gatling = item("Breakneck S4 Gatling");

    assertTrue(GameItemNamePrecedence.applyP4kName(gatling, " AD6B Ballistic Gatling ", false));
    assertEquals("Breakneck S4 Gatling", gatling.getName());
    assertTrue(GameItemNamePrecedence.applyP4kName(gatling, " AD6B Ballistic Gatling ", true));
    assertEquals("AD6B Ballistic Gatling", gatling.getName());
    assertFalse(GameItemNamePrecedence.applyP4kName(gatling, "AD6B Ballistic Gatling", true));
    assertFalse(GameItemNamePrecedence.applyP4kName(gatling, "@item_NameBEHR_Gatling", true));
    assertFalse(GameItemNamePrecedence.applyP4kName(gatling, null, true));
    assertEquals("AD6B Ballistic Gatling", gatling.getName());
  }

  @Test
  void theWikiNameReplacesAStaleUexNameOnARowTheP4kDoesNotKnow() {
    GameItem cannon = item("M5A Cannon Defense Division");

    GameItemNamePrecedence.applyWikiName(cannon, " M5A Cannon ");

    assertEquals("M5A Cannon", cannon.getName());
  }

  @Test
  void theWikiNeverRenamesARowTheP4kKnows() {
    GameItem gatling = item("AD6B Ballistic Gatling");
    gatling.setP4kSyncedAt(Instant.now());

    GameItemNamePrecedence.applyWikiName(gatling, "AD6B Gatling");

    assertEquals("AD6B Ballistic Gatling", gatling.getName());
  }

  @Test
  void aMarkupOrBlankWikiNameIsNeverWritten() {
    GameItem gatling = item("AD6B Ballistic Gatling");

    GameItemNamePrecedence.applyWikiName(gatling, "<= PLACEHOLDER =>");
    GameItemNamePrecedence.applyWikiName(gatling, " ");
    GameItemNamePrecedence.applyWikiName(gatling, null);

    assertEquals("AD6B Ballistic Gatling", gatling.getName());
  }

  @Test
  void theUexNameNeverReplacesALiveWikiName() {
    GameItem gatling = item("AD6B Ballistic Gatling");
    gatling.setScwikiName("AD6B Ballistic Gatling");

    GameItemNamePrecedence.applyUexName(gatling, "Breakneck S4 Gatling");

    assertEquals("AD6B Ballistic Gatling", gatling.getName());
  }

  @Test
  void theUexNameNeverReplacesAP4kName() {
    GameItem gatling = item("AD6B Ballistic Gatling");
    gatling.setP4kSyncedAt(Instant.now());

    GameItemNamePrecedence.applyUexName(gatling, "Breakneck S4 Gatling");

    assertEquals("AD6B Ballistic Gatling", gatling.getName());
  }

  @Test
  void theUexNameNamesARowNoOtherSourceKnows() {
    GameItem uexOnly = item("Old UEX Name");

    GameItemNamePrecedence.applyUexName(uexOnly, "New UEX Name");

    assertEquals("New UEX Name", uexOnly.getName());
  }

  @Test
  void theUexNameTakesOverOnceTheWikiDeletedItsRecord() {
    GameItem gone = item("Wiki Name");
    gone.setScwikiName("Wiki Name");
    gone.setScwikiDeletedAt(Instant.now());

    GameItemNamePrecedence.applyUexName(gone, "UEX Name");

    assertEquals("UEX Name", gone.getName());
  }

  @Test
  void theUexNameFillsABlankNameEvenOnAP4kRow() {
    GameItem blank = item(null);
    blank.setP4kSyncedAt(Instant.now());

    GameItemNamePrecedence.applyUexName(blank, "UEX Name");

    assertEquals("UEX Name", blank.getName());
  }

  @Test
  void aUsableWikiNameIsNonBlankAndFreeOfMarkup() {
    assertTrue(GameItemNamePrecedence.isUsableWikiName("M5A Cannon"));
    assertFalse(GameItemNamePrecedence.isUsableWikiName("<b>M5A</b>"));
    assertFalse(GameItemNamePrecedence.isUsableWikiName(" "));
    assertFalse(GameItemNamePrecedence.isUsableWikiName(null));
  }
}
