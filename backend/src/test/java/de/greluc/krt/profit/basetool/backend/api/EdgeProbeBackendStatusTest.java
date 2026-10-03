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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the backend half of the nightly probe (REQ-API-021, REQ-SEC-037): every admitted row's
 * request, sent without a token through the real filter chain, answers the status the generated
 * table expects — {@code 200} for the two anonymous reads, {@code 401} for every frozen operation,
 * {@code 410} for a retired one.
 */
@SpringBootTest
class EdgeProbeBackendStatusTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Sends every row of the generated table that the edge passes and compares the backend's answer.
   *
   * @throws Exception if a request cannot be performed or the model cannot be read
   */
  @Test
  @DisplayName("every admitted probe row gets the status the table expects from the backend")
  void everyAdmittedProbeRowGetsItsStatusFromTheBackend() throws Exception {
    List<EdgeAdmission.Request> rows =
        EdgeAdmission.load().probeRows().stream().filter(row -> row.status() != 404).toList();
    assertThat(rows).hasSizeGreaterThanOrEqualTo(246);

    assertThat(differences(rows))
        .as(
            "the backend answers an anonymous caller differently from the generated probe table,"
                + " so the nightly probe would fail. A frozen operation that is anonymous now is a"
                + " finding (REQ-SEC-037), not a table to adjust")
        .isEmpty();
  }

  /**
   * Proves the comparison able to fail: a refused read expected to answer {@code 200} and an
   * anonymous read expected to answer {@code 401} are both reported.
   *
   * @throws Exception if a request cannot be performed
   */
  @Test
  @DisplayName("a row the backend contradicts is reported")
  void aRowTheBackendContradictsIsReported() throws Exception {
    List<EdgeAdmission.Request> planted =
        List.of(
            new EdgeAdmission.Request("GET", "/api/v1/orders", 200),
            new EdgeAdmission.Request("GET", "/api/v1/app/version-policy", 401));

    assertThat(differences(planted)).hasSize(2);
  }

  /**
   * Sends each row without a token and collects the ones the backend answers differently.
   *
   * @param rows the rows with their expected statuses
   * @return {@code VERB path status answered X} per difference
   * @throws Exception if a request cannot be performed
   */
  private List<String> differences(List<EdgeAdmission.Request> rows) throws Exception {
    List<String> differences = new ArrayList<>();
    for (EdgeAdmission.Request row : rows) {
      int status =
          mockMvc
              .perform(request(HttpMethod.valueOf(row.method()), row.path()))
              .andReturn()
              .getResponse()
              .getStatus();
      if (status != row.status()) {
        differences.add(row.row() + " answered " + status);
      }
    }
    return differences;
  }
}
