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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class PrivacyControllerTest {

  /** The public list of approved clients the notice links. */
  private static final String APPROVED_CLIENTS_URL =
      "https://github.com/krt-profit/basetool/blob/main/docs/legal/approved-clients.md";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldReturnPrivacyView() throws Exception {
    mockMvc
        .perform(get("/privacy"))
        .andExpect(status().isOk())
        .andExpect(view().name("shell/privacy"));
  }

  /**
   * The notice renders the connected-applications section with a working link to the public list of
   * approved clients, in both languages (REQ-XCH-002).
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param heading the section heading expected in that language
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource({"de,13. Verbundene Anwendungen", "en,13. Connected applications"})
  void rendersTheConnectedApplicationsSection(String lang, String heading) throws Exception {
    mockMvc
        .perform(get("/privacy").param("lang", lang))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<h2 id=\"privacy-13\">" + heading + "</h2>")))
        .andExpect(content().string(containsString("href=\"" + APPROVED_CLIENTS_URL + "\"")));
  }

  /**
   * The connected-applications section names what job orders on a client disclose and store: the
   * read with its window and the assignee names, the creator kept until the order leaves the
   * client's set, no undo under „Verbundene Anwendungen", the withheld name missing from the
   * assignees and job orders in the legal basis, in both languages (REQ-OXCH-010).
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param read the opening of the job-order read item
   * @param stored a phrase of the stored creator item
   * @param undo the sentence that job-order changes are not undone
   * @param withheld the phrase that a withheld name is missing from the assignees
   * @param basis the phrase of the legal basis that names job orders
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "de|<strong>Aufträge lesen:</strong> die Aufträge der Einheiten, denen Sie direkt"
            + " angehören, und die Aufträge, die Sie bearbeiten – offene,"
            + " abgeschlossene bis 7 Tage nach dem Abschluss und aUEC-Aufträge, solange ihre"
            + " Abrechnung offen ist –|die Anwendung, das anlegende Mitglied und die Kennung"
            + " seiner Anfrage|Änderungen an Aufträgen nehmen Sie dort nicht zurück"
            + "|sowie bei den Bearbeitern von Aufträgen|Raffinerie, Bank und Aufträgen",
        "en|<strong>Reading job orders:</strong> the job orders of the units you belong to"
            + " directly and the job orders you work on – open ones, closed ones for up to 7"
            + " days after closing and aUEC job orders while their settlement is open –"
            + "|the application, the member who created it and the identifier of their request"
            + "|You do not undo changes to job orders there|and from the assignees of job orders"
            + "|refinery, bank and job orders"
      })
  void rendersWhatJobOrdersOnAConnectedApplicationDiscloseAndStore(
      String lang, String read, String stored, String undo, String withheld, String basis)
      throws Exception {
    String html =
        mockMvc
            .perform(get("/privacy").param("lang", lang))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains(read, stored, undo, withheld, basis).doesNotContain("??privacy.");
  }

  /**
   * The notice names the e-mail relay as a processor and states the right to object in its own
   * highlighted box, as Art. 21(4) GDPR requires, in both languages.
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param objection the heading of the objection section in that language
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource({
    "de,20. Widerspruchsrecht nach Art. 21 DSGVO",
    "en,20. Right to object under Art. 21 GDPR"
  })
  void namesTheMailRelayAndHighlightsTheRightToObject(String lang, String objection)
      throws Exception {
    String html =
        mockMvc
            .perform(get("/privacy").param("lang", lang))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("Lettermint B.V.")
        .contains("<h2 id=\"privacy-20\">" + objection + "</h2>")
        .containsPattern(
            "id=\"privacy-20\">[^<]*</h2>\\s*<div class=\"legal-callout\" role=\"note\">")
        .doesNotContain("XSRF-TOKEN")
        .doesNotContain("??privacy.");
  }

  /**
   * The notice opens with a table of contents whose every entry jumps to a heading of the text, and
   * the text sits on a card under the legal page head (REQ-UI-027).
   *
   * @throws Exception if the request fails
   */
  @Test
  void rendersAJumpListToEveryHeading() throws Exception {
    String html =
        mockMvc
            .perform(get("/privacy").param("lang", "de"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"privacy-toc\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Rechtliches<")
        .contains("class=\"card legal-doc\"")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-");
    Matcher link = Pattern.compile("href=\"#(privacy-[0-9-]+)\"").matcher(html);
    int links = 0;
    while (link.find()) {
      links++;
      assertThat(html).as("target of #%s", link.group(1)).contains("id=\"" + link.group(1) + "\"");
    }
    assertThat(links).as("one jump link per section").isEqualTo(24);
  }

  /**
   * The retention table names the seven days of a catalogue import job with the starting admin's id
   * (REQ-P4K-015), and the deletion section lists default blueprints among the admin acts kept
   * without a name (REQ-INV-017), in both languages.
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param job the retention row's data cell
   * @param window the retention row's period cell
   * @param kept the end of the list of admin acts kept after a deletion
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "de|zum Import des Spielkatalogs mit der Kennung des Administrators, der sie gestartet hat"
            + "|7 Tage nach dem Start|Patch-Wechsel, Standard-Baupl",
        "en|Catalog import jobs with the identifier of the administrator who started them"
            + "|7 days after the start|patch changes, default blueprints)"
      })
  void namesTheImportJobWindowAndTheDefaultBlueprints(
      String lang, String job, String window, String kept) throws Exception {
    mockMvc
        .perform(get("/privacy").param("lang", lang))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(job)))
        .andExpect(content().string(containsString(window)))
        .andExpect(content().string(containsString(kept)));
  }

  /**
   * The policy names the erasure register with its 12 months (REQ-DATA-048) and the three years
   * after which a deleted account's name leaves the bank ledger (REQ-DATA-047), in both languages.
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param register the register's retention row
   * @param window the register's period
   * @param bank the bank row's period
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "de|Sperrliste gel|12 Monate nach der L|3 Jahre nach der Buchung entfernt",
        "en|Erasure register of deleted accounts|12 months after the deletion"
            + "|removed 3 years after the booking"
      })
  void namesTheErasureRegisterAndTheBankNameWindow(
      String lang, String register, String window, String bank) throws Exception {
    mockMvc
        .perform(get("/privacy").param("lang", lang))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(register)))
        .andExpect(content().string(containsString(window)))
        .andExpect(content().string(containsString(bank)));
  }
}
