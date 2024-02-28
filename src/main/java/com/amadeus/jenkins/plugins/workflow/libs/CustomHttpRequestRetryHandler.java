package com.amadeus.jenkins.plugins.workflow.libs;

import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CustomHttpRequestRetryHandler extends DefaultHttpRequestRetryHandler {
    private static final int MAX_RETRIES = 2;
    private static final int RETRY_INTERVAL_SECONDS = 3;
    // remove the SSLException from the default list of non-retriable exceptions since it could be a transient error
    private static final Set<Class<? extends IOException>> NON_RETRIABLE_CLASSES = new HashSet<>(Arrays.asList(
            InterruptedIOException.class,
            UnknownHostException.class,
            ConnectException.class,
            NoRouteToHostException.class
    ));

    private final long retryIntervalMills;

    CustomHttpRequestRetryHandler() {
        super(MAX_RETRIES, false, NON_RETRIABLE_CLASSES);
        this.retryIntervalMills = TimeUnit.SECONDS.toMillis(RETRY_INTERVAL_SECONDS);
    }

    @Override
    public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
        boolean shouldRetry = super.retryRequest(exception, executionCount, context);
        if (shouldRetry) {
            try {
                Thread.sleep(retryIntervalMills);
            } catch (InterruptedException e) {
                // Restore the interrupted status
                Thread.currentThread().interrupt();
            }
        }
        return shouldRetry;
    }
}
