package com.elurea.payment_service.client;

import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.exception.ServiceUnavailableException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Map;

/**
 * Shared plumbing for the HTTP clients that call the other services.
 */
final class RestClients {

    private RestClients() {
    }

    /** A client with connect and read timeouts, so a slow service cannot hang order requests. */
    static RestClient create(String baseUrl, AppProperties.Services services) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(services.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(services.readTimeout());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** The "detail" message of the downstream problem response, or a generic message. */
    static String detailOf(RestClientResponseException ex) {
        try {
            Map<?, ?> body = ex.getResponseBodyAs(Map.class);
            if (body != null && body.get("detail") instanceof String detail) {
                return detail;
            }
        } catch (RuntimeException ignored) {
            // Body was not JSON: fall through to the generic message.
        }
        return "Request rejected by a dependent service";
    }

    static ServiceUnavailableException unavailable(String service, Exception cause) {
        return new ServiceUnavailableException(service + " call failed: " + cause.getMessage(), cause);
    }
}
