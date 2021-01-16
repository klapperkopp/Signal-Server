/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecuregcm.sms;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.VonageConfiguration;
import org.whispersystems.textsecuregcm.http.FaultTolerantHttpClient;
import org.whispersystems.textsecuregcm.http.FormDataBodyPublisher;
import org.whispersystems.textsecuregcm.util.Base64;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.ExecutorUtils;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.codahale.metrics.MetricRegistry.name;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class VonageSmsSender {

  private static final Logger         logger         = LoggerFactory.getLogger(VonageSmsSender.class);

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter          smsMeter       = metricRegistry.meter(name(getClass(), "sms", "delivered"));
  private final Meter          voxMeter       = metricRegistry.meter(name(getClass(), "vox", "delivered"));
  private final Meter          priceMeter     = metricRegistry.meter(name(getClass(), "price"));

  private final String            apiKey;
  private final String            apiSecret;
  private final ArrayList<String> numbers;
  private final String            localDomain;
  private final Random            random;

  private final FaultTolerantHttpClient httpClient;
  private final URI                     smsUri;
  private final URI                     voxUri;

  @VisibleForTesting
  public VonageSmsSender(String baseUriSms, String baseUriVoice, VonageConfiguration vonageConfiguration) {
    Executor executor = ExecutorUtils.newFixedThreadBoundedQueueExecutor(10, 100);

    this.apiKey              = vonageConfiguration.getApiKey);
    this.apiSecret           = vonageConfiguration.getApiSecret();
    this.numbers             = new ArrayList<>(vonageConfiguration.getNumbers());
    this.localDomain         = vonageConfiguration.getLocalDomain();
    this.random              = new Random(System.currentTimeMillis());
    this.smsUri              = URI.create(baseUriSms + "/sms/json");
    this.voxUri              = URI.create(baseUriVoice + "/v1/calls"   );
    this.httpClient          = FaultTolerantHttpClient.newBuilder()
                                                      .withCircuitBreaker(vonageConfiguration.getCircuitBreaker())
                                                      .withRetry(vonageConfiguration.getRetry())
                                                      .withVersion(HttpClient.Version.HTTP_2)
                                                      .withConnectTimeout(Duration.ofSeconds(10))
                                                      .withRedirect(HttpClient.Redirect.NEVER)
                                                      .withExecutor(executor)
                                                      .withName("vonage")
                                                      .build();
  }

  public VonageSmsSender(VonageConfiguration vonageConfiguration) {
      this("https://rest.nexmo.com", "https://api.nexmo.com", vonageConfiguration);
  }

  public CompletableFuture<Boolean> deliverSmsVerification(String destination, Optional<String> clientType, String verificationCode) {
    Map<String, String> requestParameters = new HashMap<>();
    requestParameters.put("api_key", apiKey);
    requestParameters.put("api_secret", apiSecret);
    
    requestParameters.put("from", "Signal"); // only in countries with Alpha Sender ID
    requestParameters.put("to", destination);

    if ("ios".equals(clientType.orElse(null))) {
      requestParameters.put("text", String.format(SmsSender.SMS_IOS_VERIFICATION_TEXT, verificationCode, verificationCode));
    } else if ("android-ng".equals(clientType.orElse(null))) {
      requestParameters.put("text", String.format(SmsSender.SMS_ANDROID_NG_VERIFICATION_TEXT, verificationCode));
    } else {
      requestParameters.put("text", String.format(SmsSender.SMS_VERIFICATION_TEXT, verificationCode));
    }

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(smsUri)
                                     .POST(FormDataBodyPublisher.of(requestParameters))
                                     .header("Content-Type", "application/x-www-form-urlencoded")
                                     .build();

    smsMeter.mark();

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                     .thenApply(this::parseResponse)
                     .handle(this::processResponse);
  }

  public CompletableFuture<Boolean> deliverVoxVerification(String destination, String verificationCode, Optional<String> locale) {
    String url = "https://" + localDomain + "/v1/calls";
    
    Ncco[] ncco = new Ncco("talk", "Your Verification Code is: " + verificationCode);
    PhoneNo[] to = new PhoneNo("phone", destination);
    PhoneNo[] from = new PhoneNo("phone", "0000");

    Map<String, String> requestParameters = new HashMap<>();
    
    requestParameters.put("ncco", ncco);
    requestParameters.put("to", to);
    requestParameters.put("from", from);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(voxUri)
                                     .POST(FormDataBodyPublisher.of(requestParameters))
                                     .header("Content-Type", "application/x-www-form-urlencoded")
                                     .header("Authorization", "Basic " + Base64.encodeBytes((apiKey + ":" + apiSecret).getBytes()))
                                     .build();

    voxMeter.mark();

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                     .thenApply(this::parseResponse)
                     .handle(this::processResponse);
  }
  
  /*
  private String getRandom(Random random, ArrayList<String> elements) {
    return elements.get(random.nextInt(elements.size()));
  }
  */

  private boolean processResponse(VonageResponse response, Throwable throwable) {
    if (response != null && response.isSuccess()) {
      priceMeter.mark((long)(response.successResponse.price * 1000));
      return true;
    } else if (response != null && response.isFailure()) {
      logger.info("Vonage request failed: " + response.failureResponse.status + ", " + response.failureResponse.message);
      return false;
    } else if (throwable != null) {
      logger.info("Vonage request failed", throwable);
      return false;
    } else {
      logger.warn("No response or throwable!");
      return false;
    }
      }

  private VonageResponse parseResponse(HttpResponse<String> response) {
    ObjectMapper mapper = SystemMapper.getMapper();

    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
        return new VonageResponse(VonageResponse.VonageSuccessResponse.fromBody(mapper, response.body()));
      } else {
        return new VonageResponse(new VonageResponse.VonageSuccessResponse());
      }
    }

    if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
      return new VonageResponse(VonageResponse.VonageFailureResponse.fromBody(mapper, response.body()));
    } else {
      return new VonageResponse(new VonageResponse.VonageFailureResponse());
    }
  }
  
  class Ncco {
    String action;
    String text;
    // class constructor
    Ncco(String action, String text){
      this.action = action;
      this.text = text;
    }
  }
  
  class PhoneNo {
    String type;
    String number;
    // class constructor
    PhoneNo(String type, String number){
      this.type = type;
      this.text = number;
    }
  }

  public static class VonageResponse {

    private VonageSuccessResponse successResponse;
    private VonageFailureResponse failureResponse;

    VonageResponse(VonageSuccessResponse successResponse) {
      this.successResponse = successResponse;
    }

    VonageResponse(VonageFailureResponse failureResponse) {
      this.failureResponse = failureResponse;
    }

    boolean isSuccess() {
      return successResponse != null;
    }

    boolean isFailure() {
      return failureResponse != null;
    }

    private static class VonageSuccessResponse {
      @JsonProperty
      private double price;

      static VonageSuccessResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, VonageSuccessResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing Vonage success response: " + e);
          return new VonageSuccessResponse();
        }
      }
    }

    private static class VonageFailureResponse {
      @JsonProperty
      private int status;

      @JsonProperty
      private String message;

      static VonageFailureResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, VonageFailureResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing Vonage success response: " + e);
          return new VonageFailureResponse();
        }
      }
    }
  }
}
