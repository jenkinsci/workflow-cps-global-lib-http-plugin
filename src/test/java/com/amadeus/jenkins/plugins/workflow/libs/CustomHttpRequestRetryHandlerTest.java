package com.amadeus.jenkins.plugins.workflow.libs;

import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

public class CustomHttpRequestRetryHandlerTest {
    private CustomHttpRequestRetryHandler handler;
    private HttpContext context;

    @BeforeEach
    public void setup() {
        handler = new CustomHttpRequestRetryHandler();
        context = new BasicHttpContext();
    }

    @Test
    public void testRetryRequestWithNonRetriableInterruptedIOException() {
        IOException exception = new InterruptedIOException();
        boolean result = handler.retryRequest(exception, 1, context);
        assertFalse(result, "Expected no retry for InterruptedIOException");
    }

    @Test
    public void testRetryRequestWithNonRetriableUnknownHostException() {
        IOException exception = new UnknownHostException();
        boolean result = handler.retryRequest(exception, 1, context);
        assertFalse(result, "Expected no retry for UnknownHostException");
    }

    @Test
    public void testRetryRequestWithNonRetriablConnectException() {
        IOException exception = new ConnectException();
        boolean result = handler.retryRequest(exception, 1, context);
        assertFalse(result, "Expected no retry for ConnectException");
    }

    @Test
    public void testRetryRequestWithNonRetriableNoRouteToHostException() {
        IOException exception = new NoRouteToHostException();
        boolean result = handler.retryRequest(exception, 1, context);
        assertFalse(result, "Expected no retry for NoRouteToHostException");
    }

    @Test
    public void testRetryRequestWithRetriableException() {
        IOException exception = new SSLHandshakeException("Handshake failed");
        boolean result = handler.retryRequest(exception, 1, context);
        assertTrue(result, "Expected retry for IOException");
    }

    @Test
    public void testRetryRequestWithMaxRetriesExceeded() {
        IOException exception = new IOException();
        boolean result = handler.retryRequest(exception, 3, context);
        assertFalse(result, "Expected no retry when max retries exceeded");
    }
}