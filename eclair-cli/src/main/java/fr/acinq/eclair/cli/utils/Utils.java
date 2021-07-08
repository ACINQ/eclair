/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.cli.utils;

import com.squareup.moshi.Moshi;
import fr.acinq.eclair.cli.utils.exceptions.ApiException;
import fr.acinq.eclair.cli.utils.exceptions.AuthenticationException;
import fr.acinq.eclair.cli.utils.exceptions.NotFoundException;
import fr.acinq.eclair.cli.utils.models.ApiError;
import okhttp3.*;
import picocli.CommandLine;

import java.util.Map;

/**
 * Created by DPA on 30/08/19.
 */
public interface Utils {

  /* ========== STATIC GLOBALS =========== */

  static final Moshi MOSHI = new Moshi.Builder().build();

  static void printErr(final CommandLine cmd, final String s, final Object... o) {
    cmd.getErr().println(String.format(s, o));
  }

  static void print(final String s, final Object... o) {
    System.out.println(String.format(s, o));
  }

  static ResponseBody http(final boolean isPrettyPrint, final String password, final String endpoint, final Map<String, Object> params) throws Exception {

    // 1 - setup okhttp client with auth
    final OkHttpClient client = new OkHttpClient.Builder()
      .authenticator((route, response) -> {
        if (response.request().header("Authorization") != null) {
          return null; // Give up, we've already attempted to authenticate.
        }
        final String credential = Credentials.basic("", password);
        return response.request().newBuilder()
          .header("Authorization", credential)
          .build();
      }).build();

    // 2 - add headers
    final Request.Builder requestBuilder = new Request.Builder()
      .url(endpoint)
      .header("User-Agent", "EclairCli");

    // 3 - add body (empty if no params)
    if (!params.isEmpty()) {
      final FormBody.Builder bodyBuilder = new FormBody.Builder();
      params.forEach((k, v) -> {
        if (k != null && v != null) {
          bodyBuilder.add(k, v.toString());
        }
      });
      requestBuilder.post(bodyBuilder.build());
    } else {
      requestBuilder
        .method("POST", RequestBody.create(null, new byte[0]))
        .header("Content-Length", "0");
    }

    // 4 - execute
    final Response response = client.newCall(requestBuilder.build()).execute();

    // 5 - handle error if user does not want the raw output
    if (isPrettyPrint && !response.isSuccessful()) {
      ApiError res = MOSHI.adapter(ApiError.class).fromJson(response.body().source());
      if (response.code() == 401) {
        throw new AuthenticationException(res.error);
      } else if (response.code() == 404) {
        throw new NotFoundException(res.error);
      } else {
        throw new ApiException(res.error);
      }
    }

    return response.body();
  }
}
