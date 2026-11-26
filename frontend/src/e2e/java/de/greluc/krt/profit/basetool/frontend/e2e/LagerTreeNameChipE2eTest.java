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

package de.greluc.krt.profit.basetool.frontend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks without a stack that a Lager tree row keeps its name readable beside a status chip on
 * every device class: no word of a name breaks across lines, and every chip stays inside its cell
 * without covering the name (REQ-UI-009, REQ-INV-002).
 */
@Tag("e2e")
class LagerTreeNameChipE2eTest {

  private static final String ORIGIN = "https://lager-tree-chip.test";

  /**
   * Both Lager trees as the templates render them: a material group with the low-stock chip, a Mein
   * Lager stack with the stolen chip, an Einheitslager stack with the relocation chip beside the
   * member and the stolen chip in its context cell, and an item group with its chips.
   */
  private static final String FIXTURE =
      """
      <!DOCTYPE html><html lang="de"><head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <link rel="stylesheet" href="/css/kernel/styles.css">
      <link rel="stylesheet" href="/css/inventory/inventory-lager.css">
      </head><body><main class="lager-page"><section class="list-panel">
      <div class="card card--flush lager-tree-card"><div class="overflow-x-auto">
      <div class="table-responsive"><div class="tree-table lager-material-tree">
        <div class="tree-row tree-head">
          <div class="tree-cell">Bestand</div><div class="tree-cell">Kontext / Zuordnung</div>
          <div class="tree-cell tree-cell--num">Qualität</div>
          <div class="tree-cell tree-cell--num">Menge</div><div class="tree-cell">Aktion</div>
        </div>
        <div class="tree-group">
          <div class="tree-row tree-row--group">
            <div class="tree-cell tree-name">
              <button type="button" class="tree-chevron" aria-expanded="true">▶</button>
              <span class="tree-label">E2E Matrix Commodity Alpha</span>
              <span class="chip chip--danger" id="group-low-stock">Niedrig</span>
            </div>
            <div class="tree-cell lager-cell-muted">2 Nutzer · 2 Stacks</div>
            <div class="tree-cell tree-cell--num">650</div>
            <div class="tree-cell tree-cell--num tree-amount">20.000 <span class="tree-unit">SCU</span></div>
            <div class="tree-cell"></div>
          </div>
          <div class="tree-group-items"><div>
            <div class="tree-row tree-row--mid stack-header">
              <div class="tree-cell tree-name">
                <button type="button" class="tree-chevron" aria-expanded="false">▶</button>
                <span class="tree-label">E2E Refinery Hub</span>
                <span class="chip chip--danger" id="my-stack-stolen">Gestohlen</span>
              </div>
              <div class="tree-cell lager-cell-context"><span class="squadron-badge">IRI</span></div>
              <div class="tree-cell tree-cell--num">650</div>
              <div class="tree-cell tree-cell--num tree-amount">12.000 <span class="tree-unit">SCU</span></div>
              <div class="tree-cell tree-cell--num lager-cell-count">1 Eintrag</div>
            </div>
          </div><div>
            <div class="tree-row tree-row--mid stack-header">
              <div class="tree-cell tree-name">
                <button type="button" class="tree-chevron" aria-expanded="false">▶</button>
                <span class="tree-label">test-member</span>
                <span class="chip chip--warning" id="unit-stack-relocation">Umzug ausstehend</span>
              </div>
              <div class="tree-cell lager-cell-context">
                <span class="squadron-badge">IRI</span>
                <span class="tree-label">E2E Refinery Hub</span>
                <span class="chip chip--danger" id="unit-stack-stolen">Gestohlen</span>
              </div>
              <div class="tree-cell tree-cell--num">700</div>
              <div class="tree-cell tree-cell--num tree-amount">8.000 <span class="tree-unit">SCU</span></div>
              <div class="tree-cell tree-cell--num lager-cell-count">1 Eintrag</div>
            </div>
          </div></div>
        </div>
      </div></div></div></div>
      <div class="card card--flush lager-tree-card"><div class="overflow-x-auto">
      <div class="table-responsive"><div class="tree-table lager-items-tree">
        <div class="tree-row tree-head">
          <div class="tree-cell">Bestand</div><div class="tree-cell">Kontext / Zuordnung</div>
          <div class="tree-cell tree-cell--num">Menge</div><div class="tree-cell">Aktion</div>
        </div>
        <div class="tree-group">
          <div class="tree-row tree-row--group">
            <div class="tree-cell tree-name">
              <button type="button" class="tree-chevron" aria-expanded="true">▶</button>
              <span class="tree-label">E2E Orderable Widget</span>
              <span class="chip chip--warning" id="item-gone">Nicht mehr im Spiel</span>
              <span class="lager-item-chips"><span class="chip" id="item-kind">Sonstiges</span></span>
              <span class="chip chip--danger" id="item-low-stock">Niedrig</span>
            </div>
            <div class="tree-cell lager-cell-muted">1 Stack</div>
            <div class="tree-cell tree-cell--num tree-amount">3 <span class="tree-unit">Stück</span></div>
            <div class="tree-cell"></div>
          </div>
          <div class="tree-group-items"><div>
            <div class="tree-row tree-row--mid stack-header">
              <div class="tree-cell tree-name">
                <button type="button" class="tree-chevron" aria-expanded="false">▶</button>
                <span class="tree-label">E2E Refinery Hub</span>
                <span class="chip chip--danger" id="item-stack-stolen">Gestohlen</span>
              </div>
              <div class="tree-cell lager-cell-context"><span class="squadron-badge">IRI</span></div>
              <div class="tree-cell tree-cell--num tree-amount">3 <span class="tree-unit">Stück</span></div>
              <div class="tree-cell tree-cell--num lager-cell-count">1 Eintrag</div>
            </div>
          </div></div>
        </div>
      </div></div></div></div>
      </section></main></body></html>
      """;

  /**
   * Lists every name word that breaks across lines and every chip that leaves its cell or covers
   * the name beside it.
   */
  private static final String PROBE =
      """
      () => {
        const findings = [];
        const box = element => element.getBoundingClientRect();
        for (const label of document.querySelectorAll('.tree-label')) {
          const text = label.firstChild;
          for (const word of text.data.matchAll(/\\S+/g)) {
            const range = document.createRange();
            range.setStart(text, word.index);
            range.setEnd(text, word.index + word[0].length);
            const lines = new Set([...range.getClientRects()].map(rect => Math.round(rect.top)));
            if (lines.size > 1) {
              findings.push(`"${word[0]}" of "${text.data}" breaks over ${lines.size} lines`);
            }
          }
        }
        for (const chip of document.querySelectorAll('.tree-cell .chip')) {
          const cell = box(chip.closest('.tree-cell'));
          const own = box(chip);
          if (own.left < cell.left - 0.5 || own.right > cell.right + 0.5) {
            findings.push(`${chip.id} spans ${own.left}-${own.right} in its cell ${cell.left}-${cell.right}`);
          }
          const label = chip.closest('.tree-cell').querySelector('.tree-label');
          const name = box(label);
          if (own.left < name.right && name.left < own.right
              && own.top < name.bottom && name.top < own.bottom) {
            findings.push(`${chip.id} covers the name "${label.textContent}"`);
          }
        }
        return findings;
      }
      """;

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the configured browser. */
  @BeforeAll
  static void openBrowser() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, false);
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void closeBrowser() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Renders the fixture at one device class and asserts that every name reads whole beside or under
   * its chips.
   *
   * @param device the device class
   */
  @ParameterizedTest
  @EnumSource(DeviceClass.class)
  void aChipNeverSqueezesTheNameBesideIt(@NotNull DeviceClass device) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setViewportSize(device.width, device.height))) {
      context.route(ORIGIN + "/**", route -> StaticFixtureServer.serve(route, FIXTURE));
      Page page = context.newPage();
      page.navigate(ORIGIN + "/");
      page.evaluate("() => document.fonts.ready.then(() => true)");
      Object findings = page.evaluate(PROBE);
      E2eSupport.capture(page, "lager-tree-name-chip-" + device.name().toLowerCase(Locale.ROOT));
      assertThat(findings)
          .as("%s: names and chips", device)
          .asInstanceOf(InstanceOfAssertFactories.LIST)
          .isEmpty();
    }
  }

  /** A device class of REQ-UI-009 by its viewport. */
  @RequiredArgsConstructor
  private enum DeviceClass {
    /** The phone class. */
    PHONE(375, 812),
    /** The tablet class at its upper bound. */
    TABLET(1024, 768),
    /** The desktop class. */
    DESKTOP(1280, 800);

    /** The viewport width in CSS pixels. */
    private final int width;

    /** The viewport height in CSS pixels. */
    private final int height;
  }
}
