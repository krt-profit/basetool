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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the order-detail template reads no {@code users} model attribute, ignoring HTML
 * comments.
 */
class OrdersDetailNoUserListTest {

  @Test
  void ordersDetailTemplateReadsNoUsersModelAttribute() throws IOException {
    String template;
    try (InputStream in =
        OrdersDetailNoUserListTest.class.getResourceAsStream("/templates/orders-detail.html")) {
      assertThat(in).as("orders-detail.html on the classpath").isNotNull();
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String withoutComments = template.replaceAll("(?s)<!--.*?-->", "");

    assertThat(withoutComments).contains("remote-users");
    assertThat(withoutComments).doesNotContainPattern("[$*]\\{\\s*users\\b");
  }
}
