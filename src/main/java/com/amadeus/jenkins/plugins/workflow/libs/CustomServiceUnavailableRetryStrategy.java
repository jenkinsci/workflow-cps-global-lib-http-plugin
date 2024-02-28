package com.amadeus.jenkins.plugins.workflow.libs;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CustomServiceUnavailableRetryStrategy implements ServiceUnavailableRetryStrategy {
    private static final int MAX_RETRIES = 2;
    private static final int RETRY_INTERVAL_SECONDS = 3;

    private final long retryInterval;

    private Set<Integer> retryOnStatusCodes = new HashSet<>(Arrays.asList(
            HttpStatus.SC_REQUEST_TIMEOUT,
            HttpStatus.SC_INTERNAL_SERVER_ERROR,
            HttpStatus.SC_BAD_GATEWAY,
            HttpStatus.SC_SERVICE_UNAVAILABLE,
            HttpStatus.SC_GATEWAY_TIMEOUT
    ));

    public CustomServiceUnavailableRetryStrategy() {
        this.retryInterval = TimeUnit.SECONDS.toMillis(RETRY_INTERVAL_SECONDS);
    }

    public CustomServiceUnavailableRetryStrategy(Set<Integer> retryOnStatusCodes) {
        this();
        Args.notNull(retryOnStatusCodes, "retryOnStatusCodes");
        this.retryOnStatusCodes = retryOnStatusCodes;
    }

    @Override
    public boolean retryRequest(HttpResponse httpResponse, int executionCount, HttpContext httpContext) {
        return executionCount <= MAX_RETRIES && retryOnStatusCodes.contains(httpResponse.getStatusLine().getStatusCode());
    }

    @Override
    public long getRetryInterval() {
        return retryInterval;
    }
}
