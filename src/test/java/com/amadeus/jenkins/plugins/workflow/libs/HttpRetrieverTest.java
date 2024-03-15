package com.amadeus.jenkins.plugins.workflow.libs;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RunWith(MockitoJUnitRunner.class)
public class HttpRetrieverTest {

    FilePath target;
    private static final String RSC_FILE = "http-lib-retriever-tests.zip";

    @Mock
    Run run;

    @Mock
    TaskListener listener;

    UsernamePasswordCredentials passwordCredentials;

    @Mock
    Jenkins jenkins;

    @Mock
    FreeStyleProject parent;

    @Mock
    Computer computer;

    @Rule
    public WireMockRule wireMock = new WireMockRule(WireMockConfiguration.options().dynamicPort());

    HttpRetrieverStub retriever;

    @org.junit.Before
    public void setUp() throws Exception {

        target = new FilePath(Files.createTempDirectory("http-lib-retriever-tests").toFile());
        Mockito.when(run.getParent()).thenReturn(parent);
        Mockito.when(jenkins.getWorkspaceFor(parent)).thenReturn(target);
        Mockito.when(listener.getLogger()).thenReturn(System.out);
        passwordCredentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "idcreds", "descr", "user", "{cGFzc3dvcmQ=}");
        createRetriever(getUrl(RSC_FILE), RSC_FILE);

        wireMock.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(5)
                        .andMatching(r -> MatchResult.of(!r.getMethod().equals(RequestMethod.HEAD) && !r.containsHeader(HttpHeaders.AUTHORIZATION)))
                        .willReturn(WireMock.unauthorized().withHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic")));
    }

    @org.junit.After
    public void tearDown() {
        target = null;
    }

    private void createRetriever(String urlToCall, String relativeUrlToServe) throws IOException {
        createRetriever(urlToCall, relativeUrlToServe, HttpURLConnection.HTTP_OK);
    }

    private void createRetriever(String urlToCall, String relativeUrlToServe, int returnForCheck) throws IOException {
        createRetriever(urlToCall, relativeUrlToServe, returnForCheck, List.of(HttpURLConnection.HTTP_OK));
    }

    private void createRetriever(String urlToCall, String relativeUrlToServe, List<Integer> returnsForDownload) throws IOException {
        createRetriever(urlToCall, relativeUrlToServe, HttpURLConnection.HTTP_OK, returnsForDownload);
    }

    private void createRetriever(String urlToCall, String relativeUrlToServe, int returnForCheck, List<Integer> returnsForDownload)
            throws IOException {
        stubForHead(relativeUrlToServe, returnForCheck);
        stubForGet(relativeUrlToServe, returnsForDownload);
        retriever = new HttpRetrieverStub(urlToCall);
    }

    private void stubForHead(String relativeUrlToServe, int returnForCheck) {
        wireMock.stubFor(
                WireMock.head(WireMock.urlMatching(".*" + relativeUrlToServe))
                        .atPriority(2)
                        .willReturn(WireMock.status(returnForCheck))
        );
    }

    private void stubForGet(String relativeUrlToServe, List<Integer> returnsForDownload) throws IOException {
        String scenario = "Scenario for " + relativeUrlToServe + " and " + returnsForDownload;
        String previousState = Scenario.STARTED;
        UrlPattern urlPattern = WireMock.urlMatching(".*" + relativeUrlToServe);
        for (int i = 0; i < returnsForDownload.size(); i++) {
            String currentState = "Request " + (i + 1);
            ResponseDefinitionBuilder responseDefinitionBuilder = returnsForDownload.get(i) == HttpURLConnection.HTTP_OK ?
                    WireMock.aResponse().withBody(getArchiveBytes(relativeUrlToServe)) :
                    WireMock.status(returnsForDownload.get(i));
            MappingBuilder mappingBuilder = WireMock.get(urlPattern)
                    .inScenario(scenario)
                    .whenScenarioStateIs(previousState)
                    .withBasicAuth("USER", "")
                    .withBasicAuth(passwordCredentials.getUsername(), passwordCredentials.getPassword().getPlainText())
                    .atPriority(2)
                    .willReturn(responseDefinitionBuilder)
                    .willSetStateTo(currentState);
            wireMock.stubFor(mappingBuilder);
            previousState = currentState;
        }
    }

    private byte[] getArchiveBytes(String relativeUrlToServe) throws IOException {
        InputStream archive = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream(relativeUrlToServe));
        return IOUtils.toByteArray(archive);
    }

    private String getUrl(String relativeUrlToServe) {
        return "http://localhost:" + wireMock.port() + "/" + relativeUrlToServe;
    }

    @Test
    public void retrieves() throws Exception {
        Assert.assertFalse(target.child("version.txt").exists());
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void firstResponseCodeToRetryThenOK() throws Exception {
        Assert.assertFalse(target.child("version.txt").exists());
        createRetriever(getUrl(RSC_FILE), RSC_FILE, Arrays.asList(HttpURLConnection.HTTP_INTERNAL_ERROR, HttpURLConnection.HTTP_OK));
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void firstAndSecondResponseCodeToRetryThenOK() throws Exception {
        Assert.assertFalse(target.child("version.txt").exists());
        createRetriever(getUrl(RSC_FILE), RSC_FILE, Arrays.asList(HttpURLConnection.HTTP_UNAVAILABLE,
                HttpURLConnection.HTTP_BAD_GATEWAY, HttpURLConnection.HTTP_OK));
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test(expected = IOException.class)
    public void firstSecondAndThirdResponseCodeToRetry() throws Exception {
        createRetriever(getUrl(RSC_FILE), RSC_FILE, Arrays.asList(HttpURLConnection.HTTP_INTERNAL_ERROR,
                HttpURLConnection.HTTP_CLIENT_TIMEOUT, HttpURLConnection.HTTP_INTERNAL_ERROR));
        Assert.assertFalse(target.child("version.txt").exists());
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
    }

    /**
     * In the Admin section 'General Security Configuration', Artifactory has an option
     * 'Hide Existence of Unauthorized Resources' which throws a 404 instead of a 401 when a resource is not authorized
     */
    @Test(expected = IOException.class)
    public void retrievesFailsByDefaultForArtifactoryWithHideUnauthorized() throws Exception {
        wireMock.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(1)
                        .andMatching(r -> MatchResult.of(!r.getMethod().equals(RequestMethod.HEAD) && !r.containsHeader(HttpHeaders.AUTHORIZATION)))
                        .willReturn(WireMock.notFound()));
        Assert.assertFalse(target.child("version.txt").exists());
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
    }

    /**
     * In the Admin section 'General Security Configuration', Artifactory has an option
     * 'Hide Existence of Unauthorized Resources' which throws a 404 instead of a 401 when a resource is not authorized
     */
    @Test
    public void retrievesForArtifactoryWithHideUnauthorizedWhenPreemptiveAuthActivated() throws Exception {
        retriever.preemptiveAuth = true;
        wireMock.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(1)
                        .andMatching(r -> MatchResult.of(!r.getMethod().equals(RequestMethod.HEAD) && !r.containsHeader(HttpHeaders.AUTHORIZATION)))
                        .willReturn(WireMock.notFound()));
        Assert.assertFalse(target.child("version.txt").exists());
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }


    @Test
    public void acceptsIncorrectVersionsDeclared() throws Exception {
        retriever.retrieve("http-lib-retriever-tests", "2.3.4", target, run, listener);
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void acceptsSharedLibEncasedInUpperLevelDirectory() throws Exception {
        createRetriever(getUrl("http-lib-retriever-tests-encased-in-upper-directory.zip"), "http-lib-retriever-tests-encased-in-upper-directory.zip");
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void acceptsNoVersionsDeclared() throws Exception {
        createRetriever(getUrl("http-lib-retriever-tests-no-version.zip"), "http-lib-retriever-tests-no-version.zip");
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void replacesVersionInUrl() throws Exception {
        createRetriever(getUrl("http-lib-retriever-test2-${library.http-lib-retriever-test2.version}.zip"), "http-lib-retriever-test2-1.2.3.zip");
        retriever.retrieve("http-lib-retriever-test2", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test(expected = IOException.class)
    public void exceptionWhenRetrieveIncorrectUrl() throws Exception {
        Assert.assertFalse(target.child("version.txt").exists());
        createRetriever("does-not-exist.zip", RSC_FILE);
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertFalse(target.child("version.txt").exists());
    }

    @Test
    public void doesNotRetrieveIfUrlNotPassed() throws Exception {
        createRetriever(null, RSC_FILE);
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertFalse(target.child("src").exists());
    }

    @Test(expected = Exception.class)
    public void doesNotRetrieveIfUrlEmpty() throws Exception {
        createRetriever("", RSC_FILE);
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
    }

    @Test(expected = IOException.class)
    public void exceptionIfUrNotFound() throws Exception {
        createRetriever(RSC_FILE, RSC_FILE, HttpURLConnection.HTTP_NOT_FOUND);
        retriever.retrieve("http-lib-retriever-tests", "1.2.3", target, run, listener);
        Assert.assertFalse(target.child("src").exists());
    }

    @Test
    public void validatesVersion() {
        FormValidation validation = retriever.validateVersion("library-name", "1.2.3");
        Assert.assertEquals(FormValidation.Kind.OK, validation.kind);
    }

    @Test
    public void validatesVersionReturnsWarningIfNotHttps() {
        retriever.httpsUsed = false;
        FormValidation validation = retriever.validateVersion("library-name", "1.2.3");
        Assert.assertEquals(FormValidation.Kind.WARNING, validation.kind);
    }

    @Test
    public void returnsWarningIfCantValidateVersion() throws IOException {
        createRetriever("http://localhost/does-not-exist.zip", RSC_FILE, HttpURLConnection.HTTP_NOT_FOUND);
        FormValidation validation = retriever.validateVersion("library-name", "1.2.3");
        Assert.assertEquals(FormValidation.Kind.WARNING, validation.kind);
    }

    @Test
    public void returnsWarningIfUnauthorizedWhenValidatesVersion() throws IOException {
        createRetriever("http://localhost/does-not-exist.zip", RSC_FILE, HttpURLConnection.HTTP_UNAUTHORIZED);
        FormValidation validation = retriever.validateVersion("library-name", "1.2.3");
        Assert.assertEquals(FormValidation.Kind.WARNING, validation.kind);
    }

    @Test(expected = IOException.class)
    public void failsIfContainsRefToParent() throws Exception {
        createRetriever(getUrl("folder-lib_hack.zip"), "folder-lib_hack.zip");
        retriever.retrieve("folder-lib_hack", "1.2.3", target, run, listener);
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }


    private class HttpRetrieverStub extends HttpRetriever {

        private boolean httpsUsed = true;
        private boolean preemptiveAuth = false;

        public HttpRetrieverStub(String url) {
            super(url, "credentialsId", false);
        }

        @Override
        Jenkins getJenkins() {
            return jenkins;
        }

        @Override
        public boolean isPreemptiveAuth() {
            return preemptiveAuth;
        }

        @Override
        UsernamePasswordCredentials findCredentials(String credentialsId) {
            return passwordCredentials;
        }

        @Override
        boolean isSecure(URL url) {
            return httpsUsed;
        }

        @Override
        UsernamePasswordCredentials initPasswordCredentials(Run<?, ?> run) {
            return passwordCredentials;
        }

        Computer getSlave() throws IOException {
            return computer;
        }

        WorkspaceList.Lease getWorkspace(FilePath dir, Computer computer) throws InterruptedException {
            return WorkspaceList.Lease.createDummyLease(dir);
        }
    }
}
