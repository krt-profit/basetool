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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The connected-application texts tell members and admins how job orders written through a client
 * are treated: never undone under „Verbundene Anwendungen", set right in the Basetool instead, and
 * without a withheld name among the assignees (REQ-OXCH-005, REQ-OXCH-010, REQ-XCH-044).
 */
class JobOrderConnectedAppsTextsTest {

  private static final ResourceBundleMessageSource MESSAGES = messages();

  private static ResourceBundleMessageSource messages() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("messages");
    messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
    messages.setFallbackToSystemLocale(false);
    return messages;
  }

  private static String text(String key, Locale locale) {
    return MESSAGES.getMessage(key, null, locale);
  }

  @Test
  void theMembersUndoPageSaysJobOrdersAreNotUndoneAndAreSetRightInTheBasetool() {
    assertThat(text("connectedApps.undo.body", Locale.GERMAN))
        .endsWith(
            " Aufträge nimmt das Zurücknehmen ebenfalls nicht zurück: Einen"
                + " stornierten Auftrag öffnest du im Basetool wieder, und Zielbetrag und"
                + " Kommentar setzt du dort zurück.");
    assertThat(text("connectedApps.undo.body", Locale.ENGLISH))
        .endsWith(
            " Job orders are not undone either: you reopen a cancelled order, and set back its"
                + " target and comment, in the Basetool.");
  }

  @Test
  void theAdminBulkUndoSaysBankRequestsAndJobOrdersAreNotUndoneAndWhereTheirChangesAreFound() {
    assertThat(text("admin.exchangeClients.undo.body", Locale.GERMAN))
        .endsWith(
            " Bankanträge und Aufträge nimmt es nicht zurück; was die Anwendung"
                + " daran geändert hat, findest du in den Audit-Logs mit dem Filter"
                + " „Client“.");
    assertThat(text("admin.exchangeClients.undo.body", Locale.ENGLISH))
        .endsWith(
            " Bank requests and job orders are not undone; you find what the application changed"
                + " in them in the Audit Logs with the Client filter.");
  }

  @Test
  void theProfileSaysAWithheldNameIsMissingFromTheAssigneesOfJobOrders() {
    assertThat(text("profile.nameWithholding.hint", Locale.GERMAN))
        .endsWith(
            " Ist es an, erscheinen deine Einträge dort ohne Namen, Kennung und Notiz, du"
                + " stehst nicht in der Mitgliederliste der Anwendung, und dein Name fehlt bei den"
                + " Bearbeitern von Aufträgen.");
    assertThat(text("profile.nameWithholding.hint", Locale.ENGLISH))
        .endsWith(
            " While it is on, your entries appear there without name, identifier and note, you"
                + " are not in the application's member list, and your name is missing from the"
                + " assignees of job orders.");
  }
}
