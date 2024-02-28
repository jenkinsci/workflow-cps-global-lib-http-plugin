package com.amadeus.jenkins.plugins.workflow.libs;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CustomServiceUnavailableRetryStrategyTest {
    private CustomServiceUnavailableRetryStrategy strategy;
    private HttpResponse httpResponse;
    private HttpContext httpContext;

    @BeforeEach
    public void setUp() {
        strategy = new CustomServiceUnavailableRetryStrategy();
        httpResponse = Mockito.mock(HttpResponse.class);
        httpContext = Mockito.mock(HttpContext.class);
    }

    @Test
    public void testRetryRequestWhenHasReachedMaxRetryCount() {
        StatusLine statusLine = Mockito.mock(StatusLine.class);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_SERVICE_UNAVAILABLE);

        assertTrue(strategy.retryRequest(httpResponse, 1, httpContext));
        assertFalse(strategy.retryRequest(httpResponse, 3, httpContext));
    }

    @Test
    public void testRetryRequestWhenResponseCodeNotToRetry() {
        StatusLine statusLine = Mockito.mock(StatusLine.class);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);

        assertFalse(strategy.retryRequest(httpResponse, 1, httpContext));
    }

    @Test
    public void testGetRetryInterval() {
        assertEquals(3000, strategy.getRetryInterval());
    }

    @Test
    public void testCustomRetryOnStatusCodes() {
        strategy = new CustomServiceUnavailableRetryStrategy(Collections.singleton(HttpStatus.SC_OK));

        StatusLine statusLine = Mockito.mock(StatusLine.class);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);

        assertTrue(strategy.retryRequest(httpResponse, 1, httpContext));
    }

    @Test
    public void testNullRetryOnStatusCodes() {
        assertThrows(IllegalArgumentException.class, () -> new CustomServiceUnavailableRetryStrategy(null));
    }
}