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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The client badge of booking requests (REQ-BANK-067), named with one registry read per page. */
@ExtendWith(MockitoExtension.class)
class BankRequestClientNamesTest {

  @Mock private ClientDirectory clientDirectory;
  @InjectMocks private BankRequestClientNames clientNames;

  @Test
  void aPageOfRequestsIsNamedWithOneRegistryRead() {
    BankBookingRequest fromVerseKit = request("versekit");
    BankBookingRequest alsoFromVerseKit = request("versekit");
    BankBookingRequest fromAGoneClient = request("retired");
    BankBookingRequest fromTheBasetool = request(null);
    when(clientDirectory.displayNames(anyCollection())).thenReturn(Map.of("versekit", "VerseKit"));

    Function<BankBookingRequest, String> names =
        clientNames.forRequests(
            List.of(fromVerseKit, alsoFromVerseKit, fromAGoneClient, fromTheBasetool));

    assertThat(names.apply(fromVerseKit)).isEqualTo("VerseKit");
    assertThat(names.apply(alsoFromVerseKit)).isEqualTo("VerseKit");
    assertThat(names.apply(fromAGoneClient)).isEqualTo("retired");
    assertThat(names.apply(fromTheBasetool)).isNull();
    verify(clientDirectory).displayNames(Set.of("versekit", "retired"));
  }

  @Test
  void requestsRaisedInTheBasetoolReadNoRegistry() {
    Function<BankBookingRequest, String> names =
        clientNames.forRequests(List.of(request(null), request(null)));

    assertThat(names.apply(request(null))).isNull();
    verifyNoInteractions(clientDirectory);
  }

  private static BankBookingRequest request(String clientId) {
    BankBookingRequest request = new BankBookingRequest();
    request.setClientId(clientId);
    return request;
  }
}
