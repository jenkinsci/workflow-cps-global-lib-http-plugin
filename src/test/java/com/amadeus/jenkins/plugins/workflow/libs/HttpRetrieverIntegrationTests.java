package com.amadeus.jenkins.plugins.workflow.libs;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import hudson.ExtensionList;
import hudson.FilePath;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Function;

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
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0"
        );
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void retrievesWithPreemptiveAuthEvenIfNotNeeded() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0",
                true
        );
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test(expected = AssertionError.class)
    public void retrievesRejectedWithoutCredentials() throws Exception {
        ExtensionList.lookupSingleton(SystemCredentialsProvider.class).getCredentials().remove(credentials);

        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "1.0"
        );
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void acceptsIncorrectVersionsDeclared() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests.zip",
                "2.3.4"
        );
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void acceptsSharedLibEncasedInUpperLevelDirectory() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests-encased-in-upper-directory.zip",
                "1.2.3"
        );
        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void acceptsSharedLibraryIfOnlyDirectoryPresentIsVarsSrcOrResources() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests-just-vars-dir.zip",
                "1.2.3"
        );
        Assert.assertTrue(target.child("vars").exists());
    }

    @Test
    public void acceptsNoVersionsDeclared() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-tests-no-version.zip",
                "2.3.4"
        );
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void replacesVersionInUrl() throws Exception {
        FilePath target = buildJobWithLibrary(
                "http-lib-retriever-test2-1.2.3.zip",
                "2.3.4"
        );
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

    @Test
    public void exceptionWhenRetrieveIncorrectUrl() throws Exception {
        boolean buildFailed = false;
        try {
            FilePath target = buildJobWithLibrary(null, "2.3.4");
            Assert.assertFalse(target.child("version.txt").exists());
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
            FilePath target = buildJobWithLibrary("http-lib-retriever-tests.zip", "1.2.3", libraryName -> null, false);
            Assert.assertFalse(target.child("src").exists());
        } catch (java.lang.AssertionError e) {
            buildFailed = true;
            e.printStackTrace();
        }
        Assert.assertTrue(buildFailed);
    }

    private @Nonnull
    FilePath buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion)
            throws Exception {
        return buildJobWithLibrary(fixtureName, libraryVersion, libraryName -> wireMock.url(libraryName + ".zip"), false);
    }

    private @Nonnull
    FilePath buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion,
                                 boolean withPreemptiveAuth) throws Exception {
        return buildJobWithLibrary(fixtureName, libraryVersion, libraryName -> wireMock.url(libraryName + ".zip"), withPreemptiveAuth);
    }

    private @Nonnull
    FilePath buildJobWithLibrary(@Nullable String fixtureName, @Nullable String libraryVersion, @Nonnull
            Function<String, String> urlBuilder, boolean withPreemptiveAuth)
            throws Exception {
        // FIXME archive name === directory

        String libraryName = "foo";

        String importScript;
        if (libraryVersion != null) {
            importScript = "@Library('" + libraryName + "@" + libraryVersion + "') _";
        } else {
            importScript = "@Library('" + libraryName + "') _";
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
                libraryName,
                new HttpRetriever(urlBuilder.apply(libraryName), credentials.getId(), withPreemptiveAuth)
        ));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(importScript, true));

        WorkflowRun run = j.buildAndAssertSuccess(p);
        return new FilePath(run.getRootDir().toPath().resolve("libs").resolve(libraryName).toFile());
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
        FilePath target = new FilePath(run.getRootDir().toPath().resolve("libs").resolve(libraryName).toFile());

        Assert.assertTrue(target.child("version.txt").exists());
        Assert.assertTrue(target.child("src").exists());
        Assert.assertTrue(target.child("vars").exists());
        Assert.assertTrue(target.child("resources").exists());
    }

}
