package com.amadeus.jenkins.plugins.workflow.libs;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.slaves.WorkspaceList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.libs.LibraryRetriever;
import org.jenkinsci.plugins.workflow.libs.LibraryRetrieverDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cloudbees.plugins.credentials.CredentialsProvider.USE_ITEM;
import static com.cloudbees.plugins.credentials.CredentialsProvider.findCredentialById;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import static com.cloudbees.plugins.credentials.CredentialsProvider.track;

/**
 * The goal of this plugin is to provide another way to retrieve shared libraries via the @Library declaration
 * in a Jenkinsfile.
 * <p>
 * The current official plugin (workflow-cps-global-lib) does provide only a way to retrieve shared libraries
 * through a SCM, such as Git, Subversion, etc.
 */
@Restricted(NoExternalUse.class)
public class HttpRetriever extends LibraryRetriever {

  private static final String HTTPS_PROTOCOL = "https";

  /**
   * The template of the URL where to retrieve a zip of the library
   * <p>
   * Replaces the pattern ${library.NAME.version} in the URL (if found in the
   * shared library URL) either by the default version specified in the admin
   * configuration page or by the user in the Jenkinsfile @Library call.
   */
  private final String httpURL;

  /**
   * Name of the credential to use if we need authentication to download the library archive
   */
  private final String credentialsId;

  /**
   * To force using authentication when downloading the library archive. Can be useful if the HTTP server
   * hides the existence of unauthorized resources by sending a 404
   * response (not found) to requests for resources that are not accessible by the user. Otherwise,
   * the response implies that the resource exists, but is protected, by requesting authentication
   * for anonymous requests (401), or by denying an authenticated request for unauthorized users.
   */
  private final boolean preemptiveAuth;

  /**
   * Constructor
   *
   * @param httpURL        URL template where the library can be downloaded
   * @param credentialsId  The credentials ID that can be used to do an authenticated download
   * @param preemptiveAuth Send the basic authentication response before the server gives an unauthorized response
   */
  @DataBoundConstructor
  public HttpRetriever(@NonNull String httpURL, @NonNull String credentialsId, boolean preemptiveAuth) {
    this.httpURL = httpURL;
    this.credentialsId = credentialsId;
    this.preemptiveAuth = preemptiveAuth;
  }

  Jenkins getJenkins() {
    return Jenkins.get();
  }

  /**
   * Accessor for know if the plugin should send the basic authentication response
   * before the server gives an unauthorized response
   *
   * @return if the plugin should always send the basic authentication
   */
  public boolean isPreemptiveAuth() {
    return this.preemptiveAuth;
  }

  /**
   * Accessor for URL template where the library can be downloaded
   *
   * @return URL template where the library can be downloaded
   */
  public String getHttpURL() {
    return this.httpURL;
  }

  /**
   * Accessor for credentials ID that can be used to do an authenticated download
   *
   * @return The credentials ID that can be used to do an authenticated download
   */
  public String getCredentialsId() {
    return credentialsId;
  }


  /**
   * Retrieves the shared library code. Prefer this version of the method.
   * <p>
   * Checks first if the library is accessible via a HEAD call.
   * Then retrieves the shared library through HTTP protocol.
   *
   * @param name     Name of the library (as specified in the Jenkinsfile @Library)
   * @param version  Version of the library (as specified in the Jenkinsfile @Library)
   * @param target   Where the code should be retrieved
   * @param run      Jenkins context
   * @param listener Only used to get the logger
   * @throws Exception if the file cannot be downloaded, archive can't be extracted, workspace is not writable
   */
  @Override
  public void retrieve(@NonNull String name, @NonNull String version, @NonNull FilePath target,
                       @NonNull Run<?, ?> run, @NonNull TaskListener listener) throws Exception {
    retrieve(name, version, true, target, run, listener);
  }


  /**
   * Checks first if the library is accessible via a HEAD call.
   * Then retrieves the shared library through HTTP protocol.
   *
   * @param name      Name of the library (as specified in the Jenkinsfile @Library)
   * @param version   Version of the library (as specified in the Jenkinsfile @Library)
   * @param changelog Not used
   * @param target    Where the code should be retrieved
   * @param run       Jenkins context
   * @param listener  Only used to get the logger
   * @throws Exception if the file cannot be downloaded, archive can't be extracted, workspace is not writable
   */
  @Override
  public void retrieve(@NonNull String name, @NonNull String version, @NonNull boolean changelog,
                       @NonNull FilePath target, @NonNull Run<?, ?> run, @NonNull TaskListener listener)
          throws Exception {

    String httpUrl = getHttpURL();
    if (httpUrl == null) {
      return;
    }
    if (httpUrl.isEmpty()) {
      throw new Exception("The URL of the shared library is empty.");
    }
    httpUrl = convertURLVersion(httpUrl, name, version);
    doRetrieve(httpUrl, name, version, target, listener, run);
  }

  private void doRetrieve(String sourceURL, String name, String version, FilePath target,
                          @NonNull TaskListener listener, Run<?, ?> run)
          throws InterruptedException, IOException, URISyntaxException {

    UsernamePasswordCredentials passwordCredentials = initPasswordCredentials(run);

    String zipFileName = FilenameUtils.getName(new URL(sourceURL).getPath());
    FilePath dir = getDownloadFolder(name, run);
    Computer computer = getSlave();

    try (WorkspaceList.Lease lease = getWorkspace(dir, computer)) {

      FilePath filePath = download(sourceURL, passwordCredentials, zipFileName, lease);
      unzip(lease, filePath);

      // Read version in version.txt if existing
      String versionMessage = "";
      String resolvedVersion = readVersion(lease.path);

      if (resolvedVersion != null) {
        resolvedVersion = resolvedVersion.trim();

        // Just in case the version.txt would contain some new lines...
        if (!resolvedVersion.equals(version)) {
          versionMessage = "Resolving version " + resolvedVersion + " of library " + name + "...\n";
        }
      }
      versionMessage += "From HTTP URL: " + sourceURL;
      listener.getLogger().println(versionMessage);

      // check to see if single directory is present, possibly encompassing the whole shared library that is configured
      if (lease.path.list().size() == 1 && lease.path.list().get(0).isDirectory()) {
          // perform a final check to make sure the upper level directory isn't just a single dir of a shared library,
          // (e.g.`src`, `vars`, or `resources`) before moving the child contents into the proper FS location
          String sharedLibUpperDir = lease.path.list().get(0).getName();
          if (!sharedLibUpperDir.equals("src") && !sharedLibUpperDir.equals("vars") && !sharedLibUpperDir.equals("resources")) {
              // move child contents of the encompassing directory
              lease.path.list().get(0).moveAllChildrenTo(lease.path);
          }
      }
      // Copying it in build folder
      lease.path.copyRecursiveTo(target);
    }
  }

  UsernamePasswordCredentials initPasswordCredentials(Run<?, ?> run) {
    final UsernamePasswordCredentials passwordCredentials;
    StandardUsernameCredentials credentials = findCredentialById(credentialsId, StandardUsernameCredentials.class, run);
    if (credentials instanceof UsernamePasswordCredentials) {
      passwordCredentials = (UsernamePasswordCredentials) credentials;
      track(getJenkins(), passwordCredentials);
    } else {
      passwordCredentials = null;
    }
    return passwordCredentials;
  }

  UsernamePasswordCredentials initPasswordCredentials() {
    if (!getJenkins().hasPermission(Jenkins.ADMINISTER)) {
      return null;
    }
    UsernamePasswordCredentials passwordCredentials = findCredentials(credentialsId);
    if (passwordCredentials != null) {
      track(getJenkins(), passwordCredentials);
      return passwordCredentials;
    }
    return null;
  }

  UsernamePasswordCredentials findCredentials(String credentialsId) {
    List<StandardUsernameCredentials> standardUsernameCredentials = lookupCredentials(
            StandardUsernameCredentials.class, getJenkins(), ACL.SYSTEM, Collections.emptyList());
    for (StandardUsernameCredentials standardUsernameCredential : standardUsernameCredentials) {
      if (standardUsernameCredential instanceof UsernamePasswordCredentials) {
        UsernamePasswordCredentials passwordCredentials = (UsernamePasswordCredentials) standardUsernameCredential;
        if (standardUsernameCredential.getId() != null && standardUsernameCredential.getId().equals(credentialsId)) {
          return passwordCredentials;
        }
      }
    }
    return null;
  }

  private void unzip(WorkspaceList.Lease lease, FilePath filePath) throws IOException, InterruptedException {
    filePath.unzip(lease.path);
    // Delete the archive
    filePath.delete();
  }

  private FilePath download(String sourceURL, UsernamePasswordCredentials passwordCredentials,
                            String zipFileName, WorkspaceList.Lease lease)
          throws IOException, URISyntaxException {
    URL url = new URL(sourceURL);
    HttpGet get = new HttpGet(url.toURI());
    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpClientContext context = getHttpClientContext(passwordCredentials, url);
      try (CloseableHttpResponse response = client.execute(get, context)) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
          throw new IOException("Failed to download " + sourceURL + ". Returned code: " + statusCode);
        }
        return writeResponseToFile(zipFileName, lease, response);
      }
    }
  }

  private FilePath writeResponseToFile(String zipFileName, WorkspaceList.Lease lease, HttpResponse response) throws IOException {
    HttpEntity entity = response.getEntity();
    try (InputStream inputStream = entity.getContent()) {
      String wholeFilenameWithTargetPath = lease.path.child(zipFileName).getRemote();
      File file = new File(wholeFilenameWithTargetPath);
      if (file.getParentFile().exists() || file.getParentFile().mkdirs()) {
        Files.copy(inputStream, Paths.get(wholeFilenameWithTargetPath),
                StandardCopyOption.REPLACE_EXISTING);
        return new FilePath(file);
      } else {
        throw new IOException("Could not create the folders for " + wholeFilenameWithTargetPath);
      }
    }
  }

  private CredentialsProvider getCredentialsProvider(UsernamePasswordCredentials passwordCredentials) {
    if (passwordCredentials != null) {
      BasicCredentialsProvider provider = new BasicCredentialsProvider();
      String username = passwordCredentials.getUsername();
      String password = passwordCredentials.getPassword().getPlainText();
      org.apache.http.auth.UsernamePasswordCredentials credentials
              = new org.apache.http.auth.UsernamePasswordCredentials(username, password);
      provider.setCredentials(AuthScope.ANY, credentials);
      return provider;
    }
    return null;
  }

  WorkspaceList.Lease getWorkspace(FilePath dir, Computer computer) throws InterruptedException {
    return computer.getWorkspaceList().allocate(dir);
  }

  private FilePath getDownloadFolder(String name, Run<?, ?> run) throws IOException {
    FilePath dir;
    if (run.getParent() instanceof TopLevelItem) {
      FilePath baseWorkspace = getJenkins().getWorkspaceFor((TopLevelItem) run.getParent());
      if (baseWorkspace == null) {
        throw new IOException(getJenkins().getDisplayName() + " may be offline");
      }
      dir = baseWorkspace.withSuffix(getFilePathSuffix() + "libs").child(name);
    } else {
      throw new AbortException("Cannot check out in non-top-level build");
    }
    return dir;
  }

  Computer getSlave() throws IOException {
    Computer computer = getJenkins().toComputer();
    if (computer == null) {
      throw new IOException(getJenkins().getDisplayName() + " may be offline");
    }
    return computer;
  }

  // There is WorkspaceList.tempDir but no API to make other variants
  private static String getFilePathSuffix() {
    return System.getProperty(WorkspaceList.class.getName(), "@");
  }

  /**
   * Validates version. Is it available for download. Tests the URL.
   * Used if a default version is referenced in the declaration of the library.
   * Then the archive of the library must be reachable.
   * <p>
   * Replaces the pattern ${library.NAME.version} in the URL (if found in the
   * shared library URL) either by the default version specified in the admin
   * configuration page or by the user in the Jenkinsfile @Library call.
   *
   * @param name    Name of the library
   * @param version Version of the library
   * @return Result of the validation
   */
  @Override
  public FormValidation validateVersion(@NonNull String name, @NonNull String version) {
    String replacedVersionURL = convertURLVersion(httpURL, name, version);
    try {
      URL newURL = new URL(replacedVersionURL);

      switch (checkURL(newURL)) {
        case HttpStatus.SC_OK:
          return validateVersionIfCheckIsOk(newURL, version);
        case HttpStatus.SC_UNAUTHORIZED:
          return FormValidation.warning("You are not authorized to access to this URL...");
        default:
          return FormValidation.warning("This URL does not exist...");
      }
    } catch (IOException | URISyntaxException e) {
      return FormValidation.warning(e, "Cannot validate default version.");
    }
  }

  private FormValidation validateVersionIfCheckIsOk(URL url, String version) {
    String valid = "Version " + version + " is valid.";
    if (isSecure(url)) {
      return FormValidation.ok(valid);
    } else {
      return FormValidation.warning(valid + " But the protocol is insecure... "
              + "Consider switching to HTTPS, particularly if you are using Credentials.");
    }
  }

  boolean isSecure(URL url) {
    return HTTPS_PROTOCOL.equals(url.getProtocol());
  }

  private String readVersion(FilePath filePath) throws IOException, InterruptedException {
    try (InputStream inputStream = filePath.child("version.txt").read()) {
      return IOUtils.toString(inputStream);
    } catch (FileNotFoundException | NoSuchFileException e) {
      Logger.getLogger(HttpRetriever.class.getName())
              .log(Level.FINER, "version.txt not found in the archive.", e);
      return null;
    }

  }

  // ------------ UTIL ------------ //

  private String convertURLVersion(String url, String name, String version) {
    Pattern p = Pattern.compile("\\$\\{library." + name + ".version\\}");
    Matcher match = p.matcher(url);

    return match.find() ? match.replaceAll(version) : url;
  }

  private int checkURL(URL url) throws IOException, URISyntaxException {
    UsernamePasswordCredentials passwordCredentials = initPasswordCredentials();
    HttpHead head = new HttpHead(url.toURI());
    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpClientContext context = getHttpClientContext(passwordCredentials, url);
      try (CloseableHttpResponse response = client.execute(head, context)) {
        return response.getStatusLine().getStatusCode();
      }
    }
  }

  private HttpClientContext getHttpClientContext(UsernamePasswordCredentials passwordCredentials, URL url) {
    HttpClientContext context = HttpClientContext.create();
    // Authenticate if credentials are given
    if (passwordCredentials != null) {
      CredentialsProvider credentialsProvider = getCredentialsProvider(passwordCredentials);
      context.setCredentialsProvider(credentialsProvider);
    }
    if (isPreemptiveAuth()) {
      setPreemptiveAuth(context, url);
    }
    return context;
  }

  private void setPreemptiveAuth(HttpClientContext context, URL url) {
    // Create AuthCache instance
    AuthCache authCache = new BasicAuthCache();
    // Generate BASIC scheme object and add it to the local
    // auth cache
    BasicScheme basicAuth = new BasicScheme();
    HttpHost target = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
    authCache.put(target, basicAuth);

    // Add AuthCache to the execution context
    context.setAuthCache(authCache);
  }

  // ---------- DESCRIPTOR ------------ //

  @Override
  public LibraryRetrieverDescriptor getDescriptor() {
    return super.getDescriptor();
  }

  @Symbol("http")
  @Extension
  @Restricted(NoExternalUse.class)
  public static class DescriptorImpl extends LibraryRetrieverDescriptor {

    @Override
    public @NonNull
    String getDisplayName() {
      return "HTTP";
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
                                                 @QueryParameter String credentialsId) {
      StandardListBoxModel result = new StandardListBoxModel();
      result.includeEmptyValue();

      if (item == null) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
          return result.includeCurrentValue(credentialsId);
        }
      } else {
        if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(USE_ITEM)) {
          return result.includeCurrentValue(credentialsId);
        }
      }
      List<StandardUsernameCredentials> standardUsernameCredentials = lookupCredentials(
              StandardUsernameCredentials.class, item, ACL.SYSTEM, Collections.emptyList());
      for (StandardUsernameCredentials standardUsernameCredential : standardUsernameCredentials) {
        result.with(standardUsernameCredential);
      }
      return result;
    }

  }

}
