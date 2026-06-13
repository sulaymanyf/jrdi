package io.jrdi.resolver;

import io.jrdi.core.coord.Gav;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides where to fetch a remote artifact from and how to authenticate. The plan is
 * computed lazily and cached per {@link Gav}+classifier pair.
 *
 * <p>Algorithm: collect all {@code <repositories>} (across profiles) + plugin repositories
 * → apply mirror rewriting → walk the list in order, returning the first that returns
 * HTTP 200 for the artifact's expected URL.
 */
public final class RemotePlan {

    private static final Logger LOG = LoggerFactory.getLogger(RemotePlan.class);

    private final List<Remote> remotes;

    public RemotePlan(Settings settings) {
        this.remotes = buildRemotes(settings);
        LOG.info("remote plan: {}", remotes.stream().map(r -> r.id).toList());
    }

    public Optional<Path> downloadJar(Gav gav, String classifier) {
        String rel = relativePath(gav, classifier);
        for (Remote r : remotes) {
            URL url;
            try {
                url = URI.create(r.baseUrl + "/" + rel).toURL();
            } catch (java.net.MalformedURLException e) {
                LOG.warn("malformed remote URL {}: {}", r.baseUrl + "/" + rel, e.getMessage());
                continue;
            }
            Optional<Path> got = tryDownload(url, r);
            if (got.isPresent()) {
                LOG.debug("downloaded {} from {}", rel, url);
                return got;
            }
        }
        return Optional.empty();
    }

    static String relativePath(Gav gav, String classifier) {
        String groupPath = gav.group().replace('.', '/');
        String name = classifier.equals("jar")
                ? gav.artifact() + "-" + gav.version() + ".jar"
                : gav.artifact() + "-" + gav.version() + "-" + classifier + ".jar";
        return groupPath + "/" + gav.artifact() + "/" + gav.version() + "/" + name;
    }

    private Optional<Path> tryDownload(URL url, Remote r) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestMethod("GET");
            con.setInstanceFollowRedirects(true);
            if (r.basicAuth != null) {
                con.setRequestProperty("Authorization", r.basicAuth);
            }
            int code = con.getResponseCode();
            if (code != 200) {
                LOG.debug("GET {} -> {}", url, code);
                return Optional.empty();
            }
            Path tmp = Files.createTempFile("jrdi-fetch-", ".part");
            try (InputStream in = con.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(tmp);
        } catch (IOException e) {
            LOG.debug("GET {} failed: {}", url, e.getMessage());
            return Optional.empty();
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static List<Remote> buildRemotes(Settings settings) {
        List<Remote> out = new ArrayList<>();
        // 1. Collect all repositories (settings-level + profile-level)
        List<Repository> rawRepos = new ArrayList<>();
        for (org.apache.maven.settings.Profile p : settings.getProfiles()) {
            rawRepos.addAll(p.getRepositories());
            rawRepos.addAll(p.getPluginRepositories());
        }
        // 2. Apply mirror rewriting
        for (Repository r : rawRepos) {
            String url = r.getUrl();
            String id = r.getId();
            for (Mirror m : settings.getMirrors()) {
                if (matches(m.getMirrorOf(), id)) {
                    url = m.getUrl();
                    id = m.getId();
                    break;
                }
            }
            String auth = authFor(settings, id);
            out.add(new Remote(id, stripTrailingSlash(url), auth));
        }
        // 3. If nothing configured, default to central
        if (out.isEmpty()) {
            out.add(new Remote("central", "https://repo.maven.apache.org/maven2", null));
        }
        return out;
    }

    private static boolean matches(String mirrorOf, String id) {
        if (mirrorOf == null || mirrorOf.isEmpty()) return false;
        for (String t : mirrorOf.split(",")) {
            String s = t.trim();
            if (s.equals("*") || s.equals(id) || s.equals("external:*") || s.startsWith("external:")) {
                return true;
            }
        }
        return false;
    }

    private static String authFor(Settings settings, String repoId) {
        for (Server s : settings.getServers()) {
            if (s.getId().equals(repoId) && s.getUsername() != null && !s.getUsername().isEmpty()) {
                String userPass = s.getUsername() + ":" + (s.getPassword() == null ? "" : s.getPassword());
                return "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes());
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static final class Remote {
        final String id;
        final String baseUrl;
        final String basicAuth;

        Remote(String id, String baseUrl, String basicAuth) {
            this.id = id;
            this.baseUrl = baseUrl;
            this.basicAuth = basicAuth;
        }

        @Override
        public String toString() {
            return id + "[" + baseUrl + "]";
        }
    }
}
