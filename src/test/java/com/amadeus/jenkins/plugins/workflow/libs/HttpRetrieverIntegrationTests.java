package com.amadeus.jenkins.plugins.workflow.libs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ExtensionList;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HttpRetrieverIntegrationTests {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public WireMockRule wireMock = new WireMockRule(WireMockConfiguration.options().dynamicPort());

    private UsernamePasswordCredentialsImpl credentials;
    private GlobalLibraries globalLibraries;

    @Before
    public void setUp() {
        credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "someCredentials", null, "username", "password");
        ExtensionList.lookupSingleton(SystemCredentialsProvider.class).getCredentials().add(credentials);
        globalLibraries = ExtensionList.lookupSingleton(GlobalLibraries.class);

        wireMock.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(1)
                        .andMatching(r -> MatchResult.of(!r.getMethod().equals(RequestMethod.HEAD) && !r.containsHeader(HttpHeaders.AUTHORIZATION)))
                        .willReturn(WireMock.unauthorized().withHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic")));
    }

    @Test
    public void retrieves() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0"
        );
        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void retrievesWithPreemptiveAuthEvenIfNotNeeded() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0",
                true
        );
        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test(expected = AssertionError.class)
    public void retrievesRejectedWithoutCredentials() throws Exception {
        ExtensionList.lookupSingleton(SystemCredentialsProvider.class).getCredentials().remove(credentials);

        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0"
        );
        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void acceptsIncorrectVersionsDeclared() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "2.3.4"
        );
        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void acceptsSharedLibEncasedInUpperLevelDirectory() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests-encased-in-upper-directory.zip",
                "1.2.3"
        );
        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void acceptsSharedLibraryIfOnlyDirectoryPresentIsVarsSrcOrResources() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests-just-vars-dir.zip",
                "1.2.3"
        );
        Assert.assertTrue(Files.exists(target.resolve("vars")));
    }

    @Test
    public void acceptsNoVersionsDeclared() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-tests-no-version.zip",
                "2.3.4"
        );
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void replacesVersionInUrl() throws Exception {
        Path target = buildJobWithLibrary(
                "http-lib-retriever-test2-1.2.3.zip",
                "2.3.4"
        );
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void exceptionWhenRetrieveIncorrectUrl() throws Exception {
        boolean buildFailed = false;
        try {
            Path target = buildJobWithLibrary(null, "2.3.4");
            Assert.assertFalse(Files.exists(target.resolve("version.txt")));
        } catch (java.lang.AssertionError e) {
            buildFailed = true;
            //e.printStackTrace();
        }
        Assert.assertTrue(buildFailed);
    }

    @Test
    public void doesNotRetrieveIfUrlNotPassed() throws Exception {
        boolean buildFailed = false;
        try {
            Path target = buildJobWithLibrary("http-lib-retriever-tests.zip", "1.2.3", libraryName -> null, false);
            Assert.assertFalse(Files.exists(target.resolve("src")));
        } catch (java.lang.AssertionError e) {
            buildFailed = true;
            e.printStackTrace();
        }
        Assert.assertTrue(buildFailed);
    }

    @Test
    public void retrievesWithPreemptiveAuthIfNeeded() throws Exception {
        String libraryName = "foo";
        String importScript = "@Library('foo@1.0') _";
        String fixtureName = "http-lib-retriever-tests.zip";
        InputStream archive = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream(fixtureName));
        wireMock.stubFor(
                WireMock.get(WireMock.anyUrl())
                        .atPriority(2)
                        .withBasicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                        .willReturn(WireMock.aResponse().withBody(IOUtils.toByteArray(archive)))

        );
        wireMock.stubFor(
                WireMock.any(WireMock.anyUrl())
                        .atPriority(1)
                        .andMatching(r -> MatchResult.of(!r.getMethod().equals(RequestMethod.HEAD) && !r.containsHeader(HttpHeaders.AUTHORIZATION)))
                        .willReturn(WireMock.notFound()));
        globalLibraries.getLibraries().add(new LibraryConfiguration(
                libraryName,
                new HttpRetriever(wireMock.url(libraryName + ".zip"), credentials.getId(), true)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        WorkflowRun run = j.buildAndAssertSuccess(p);
        List<Path> children;
        try (Stream<Path> list = Files.list(run.getRootDir().toPath().resolve("libs"))) {
            children = list.filter(Files::isDirectory).collect(Collectors.toList());
        }
        assertThat(children, hasSize(1));
        Path target = children.get(0);

        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void firstResponseCodeToRetryThenOK() throws Exception {
        String libraryName = "foo";
        String importScript = "@Library('foo@1.0') _";
        String fixtureName = "http-lib-retriever-tests.zip";
        wireMock.stubFor(
                WireMock.head(WireMock.anyUrl())
                        .atPriority(2)
                        .willReturn(WireMock.ok())
        );
        stubForGet(fixtureName, List.of(HttpURLConnection.HTTP_INTERNAL_ERROR, HttpURLConnection.HTTP_OK));
        globalLibraries.getLibraries().add(new LibraryConfiguration(
                libraryName,
                new HttpRetriever(wireMock.url(libraryName + ".zip"), credentials.getId(), true)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        WorkflowRun run = j.buildAndAssertSuccess(p);
        List<Path> children;
        try (Stream<Path> list = Files.list(run.getRootDir().toPath().resolve("libs"))) {
            children = list.filter(Files::isDirectory).collect(Collectors.toList());
        }
        assertThat(children, hasSize(1));
        Path target = children.get(0);

        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void firstAndSecondResponseCodeToRetryThenOK() throws Exception {
        String libraryName = "foo";
        String importScript = "@Library('foo@1.0') _";
        String fixtureName = "http-lib-retriever-tests.zip";
        wireMock.stubFor(
                WireMock.head(WireMock.anyUrl())
                        .atPriority(2)
                        .willReturn(WireMock.ok())
        );
        stubForGet(fixtureName, List.of(HttpURLConnection.HTTP_BAD_GATEWAY, HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HttpURLConnection.HTTP_OK));
        globalLibraries.getLibraries().add(new LibraryConfiguration(
                libraryName,
                new HttpRetriever(wireMock.url(libraryName + ".zip"), credentials.getId(), true)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        WorkflowRun run = j.buildAndAssertSuccess(p);
        List<Path> children;
        try (Stream<Path> list = Files.list(run.getRootDir().toPath().resolve("libs"))) {
            children = list.filter(Files::isDirectory).collect(Collectors.toList());
        }
        assertThat(children, hasSize(1));
        Path target = children.get(0);

        Assert.assertTrue(Files.exists(target.resolve("version.txt")));
        Assert.assertTrue(Files.exists(target.resolve("src")));
        Assert.assertTrue(Files.exists(target.resolve("vars")));
        Assert.assertTrue(Files.exists(target.resolve("resources")));
    }

    @Test
    public void firstSecondAndThirdResponseCodeToRetryThenException() throws Exception {
        String libraryName = "foo";
        String importScript = "@Library('foo@1.0') _";
        String fixtureName = "http-lib-retriever-tests.zip";
        wireMock.stubFor(
                WireMock.head(WireMock.anyUrl())
                        .atPriority(2)
                        .willReturn(WireMock.ok())
        );
        stubForGet(fixtureName, List.of(HttpURLConnection.HTTP_BAD_GATEWAY, HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HttpURLConnection.HTTP_GATEWAY_TIMEOUT, HttpURLConnection.HTTP_OK));
        globalLibraries.getLibraries().add(new LibraryConfiguration(
                libraryName,
                new HttpRetriever(wireMock.url(libraryName + ".zip"), credentials.getId(), true)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        boolean buildFailed = false;
        try {
            j.buildAndAssertSuccess(p);
        } catch (java.lang.AssertionError e) {
            e.printStackTrace();
            buildFailed = true;
        }
        Assert.assertTrue("Expected build to fail since too many retries", buildFailed);
    }

    private @NonNull
    Path buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion)
            throws Exception {
        return buildJobWithLibrary(fixtureName, libraryVersion, libraryName -> wireMock.url(libraryName + ".zip"), false);
    }

    private @NonNull
    Path buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion,
                             boolean withPreemptiveAuth) throws Exception {
        return buildJobWithLibrary(fixtureName, libraryVersion, libraryName -> wireMock.url(libraryName + ".zip"), withPreemptiveAuth);
    }

    private @NonNull
    Path buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion, @NonNull
            Function<String, String> urlBuilder, boolean withPreemptiveAuth)
            throws Exception {
        String importScript;
        if (libraryVersion != null) {
            importScript = "@Library('foo@" + libraryVersion + "') _";
        } else {
            importScript = "@Library('foo') _";
        }

        if (fixtureName != null) {
            InputStream archive = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream(fixtureName));

            wireMock.stubFor(
                    WireMock.head(WireMock.anyUrl())
                            .atPriority(2)
                            .willReturn(WireMock.ok())
            );
            wireMock.stubFor(
                    WireMock.get(WireMock.anyUrl())
                            .atPriority(2)
                            .withBasicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                            .willReturn(WireMock.aResponse().withBody(IOUtils.toByteArray(archive)))

            );
        } else {
            wireMock.stubFor(
                    WireMock.head(WireMock.anyUrl())
                            .atPriority(2)
                            .willReturn(WireMock.notFound()));
            wireMock.stubFor(
                    WireMock.get(WireMock.anyUrl())
                            .atPriority(2)
                            .willReturn(WireMock.notFound()));
        }
        globalLibraries.getLibraries().add(new LibraryConfiguration(
                "foo",
                new HttpRetriever(urlBuilder.apply("foo"), credentials.getId(), withPreemptiveAuth)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        WorkflowRun run = j.buildAndAssertSuccess(p);
        List<Path> children;
        try (Stream<Path> list = Files.list(run.getRootDir().toPath().resolve("libs"))) {
            children = list.filter(Files::isDirectory).collect(Collectors.toList());
        }
        assertThat(children, hasSize(1));
        return children.get(0);
    }

    private void stubForGet(@Nullable String fixtureName, List<Integer> returnsForDownload) throws IOException {
        InputStream archive = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream(fixtureName));
        String scenario = "Scenario for fixtureName " + fixtureName + " and returnsForDownload " + returnsForDownload;
        String previousState = Scenario.STARTED;
        UrlPattern urlPattern = WireMock.anyUrl();
        for (int i = 0; i < returnsForDownload.size(); i++) {
            String currentState = "Request " + (i + 1);
            ResponseDefinitionBuilder responseDefinitionBuilder = returnsForDownload.get(i) == HttpURLConnection.HTTP_OK ?
                    WireMock.aResponse().withBody(IOUtils.toByteArray(archive)) :
                    WireMock.status(returnsForDownload.get(i));
            MappingBuilder mappingBuilder = WireMock.get(urlPattern)
                    .inScenario(scenario)
                    .whenScenarioStateIs(previousState)
                    .withBasicAuth(credentials.getUsername(), credentials.getPassword().getPlainText())
                    .atPriority(2)
                    .willReturn(responseDefinitionBuilder)
                    .willSetStateTo(currentState);
            wireMock.stubFor(mappingBuilder);
            previousState = currentState;
        }
    }
}
