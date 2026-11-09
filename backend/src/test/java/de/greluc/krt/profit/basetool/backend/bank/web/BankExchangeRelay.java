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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends bank reads to the backend's exchange surface as the ingest gateway relays them, for a
 * registry client granted {@code exchange.bank.read} and {@code exchange.bank.queue}, inside the
 * caller's transaction.
 *
 * <p>Registering the client and switching the exchange and the bank on happen in the caller's
 * transaction, so a rolled-back test leaves neither behind.
 */
final class BankExchangeRelay {

  /** The bank route family on the backend. */
  static final String BANK = "/api/v1/exchange/bank";

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "b".repeat(39);

  private final MockMvc mockMvc;
  private final JsonMapper json = JsonMapper.builder().build();
  private final String client = "bank-" + UUID.randomUUID().toString().substring(0, 8);

  /**
   * Registers the client and switches the exchange and the bank on.
   *
   * @param context the running web application context
   */
  BankExchangeRelay(@NotNull WebApplicationContext context) {
    this.mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("Bank client");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.BANK_READ,
            ExchangeCapability.BANK_QUEUE));
    context.getBean(ExchangeClientRepository.class).saveAndFlush(registered);
    ExchangeSettingsRepository settingsRepository =
        context.getBean(ExchangeSettingsRepository.class);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(true);
    settings.setBankEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  /**
   * The registry client id the reads are relayed for.
   *
   * @return the client id
   */
  @NotNull
  String client() {
    return client;
  }

  /**
   * Sends one relayed {@code GET} below {@link #BANK}.
   *
   * @param member the acting member
   * @param path the path below {@link #BANK}
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  MockHttpServletResponse read(
      @NotNull UUID member, @NotNull String path, @NotNull Map<String, String> parameters)
      throws Exception {
    MockHttpServletRequestBuilder call = relayed(get(BANK + path), member);
    parameters.forEach(call::param);
    return mockMvc.perform(call).andReturn().getResponse();
  }

  /**
   * Parses a response body.
   *
   * @param response the response
   * @return the body as JSON
   * @throws Exception when the body is no JSON
   */
  @NotNull
  JsonNode body(@NotNull MockHttpServletResponse response) throws Exception {
    String content = response.getContentAsString();
    return json.readTree(content.isBlank() ? "null" : content);
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param member the acting member
   * @return the relayed request
   */
  @NotNull
  private MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull UUID member) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(
            ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER,
            ExchangeCapability.BANK_READ.getScope()
                + ","
                + ExchangeCapability.BANK_QUEUE.getScope())
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}
