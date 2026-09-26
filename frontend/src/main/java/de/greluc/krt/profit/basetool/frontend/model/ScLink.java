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

package de.greluc.krt.profit.basetool.frontend.model;

import java.net.URI;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * One external Star Citizen website listed on the „Star-Citizen-Links" page (REQ-UI-025).
 *
 * <p>Name and description come from {@code scLinks.link.<key>.name} / {@code .description}; the
 * logo is a local copy under {@code /images/sc-links/}, because the CSP allows no foreign images.
 */
@Getter
@RequiredArgsConstructor
public enum ScLink {
  DAS_KARTELL(
      "dasKartell", "https://das-kartell.org/", "das-kartell.png", ScLinkCategory.ORGANISATION),
  KRT_OPSEC("krtOpsec", "https://krt-opsec.de/", "krt-opsec.png", ScLinkCategory.ORGANISATION),
  UEX("uex", "https://uexcorp.space/", "uex.png", ScLinkCategory.TRADE),
  SC_CARGO("scCargo", "https://sc-cargo.space/", "sc-cargo.png", ScLinkCategory.TRADE),
  SC_HAULING("scHauling", "https://sc-hauling.tools/", "sc-hauling.png", ScLinkCategory.TRADE),
  HAULER("hauler", "https://hauler.thespacecoder.space/", "hauler.png", ScLinkCategory.TRADE),
  ERKUL("erkul", "https://erkul.games/calculator", "erkul.png", ScLinkCategory.SHIPS),
  SPVIEWER("spviewer", "https://www.spviewer.eu/", "spviewer.png", ScLinkCategory.SHIPS),
  FLEETYARDS("fleetyards", "https://fleetyards.net/", "fleetyards.png", ScLinkCategory.SHIPS),
  CCU_GAME("ccuGame", "https://ccugame.app/", "ccugame.png", ScLinkCategory.SHIPS),
  ADI_MAPS("adiMaps", "https://maps.adi.sc/", "adi-maps.png", ScLinkCategory.SHIPS),
  CORNERSTONE("cornerstone", "https://cstone.space/", "cstone.png", ScLinkCategory.DATABASES),
  SCMDB("scmdb", "https://scmdb.net/", "scmdb.svg", ScLinkCategory.DATABASES),
  SC_CRAFT("scCraft", "https://sc-craft.tools/", "sc-craft.png", ScLinkCategory.DATABASES),
  VERSEKIT(
      "versekit", "https://xharig.github.io/VerseKit/", "versekit.png", ScLinkCategory.DATABASES),
  VERSEGUIDE("verseguide", "https://verseguide.com/", "verseguide.png", ScLinkCategory.UNIVERSE),
  UEEXI("ueexi", "https://ueexi.com/", "ueexi.svg", ScLinkCategory.UNIVERSE),
  SC_CHARACTERS(
      "scCharacters",
      "https://www.star-citizen-characters.com/",
      "sc-characters.png",
      ScLinkCategory.UNIVERSE),
  DAYMAR_RALLY(
      "daymarRally", "https://www.daymarrally.com/", "daymar-rally.png", ScLinkCategory.UNIVERSE);

  /** Suffix of the message keys {@code scLinks.link.<key>.name} and {@code .description}. */
  @NotNull private final String key;

  /** Absolute HTTPS address the card opens in a new tab. */
  @NotNull private final String url;

  /** File name of the logo under {@code /images/sc-links/}. */
  @NotNull private final String icon;

  /** Section of the page the card is rendered in. */
  @NotNull private final ScLinkCategory category;

  /**
   * Returns the host shown under the site's name, so a member sees where the card leads.
   *
   * @return the host of {@link #url} without a leading {@code www.}
   */
  @NotNull
  public String displayHost() {
    String host = URI.create(url).getHost();
    return host.startsWith("www.") ? host.substring(4) : host;
  }
}
