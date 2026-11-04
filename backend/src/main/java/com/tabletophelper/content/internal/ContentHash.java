package com.tabletophelper.content.internal;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The SHA-256 of a loader's files and code version (D169), as 64 hex digits: its name and version, then each file's
 * name and bytes, in name order, so that the same files and code give the same hash wherever the jar runs.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(ContentLoader loader) {
        MessageDigest digest = sha256();
        update(digest, loader.name() + "\n" + loader.version() + "\n");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        byte[] buffer = new byte[64 * 1024];
        for (String pattern : loader.files()) {
            Resource[] resources;
            try {
                resources = resolver.getResources("classpath:" + pattern);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list " + pattern + " for " + loader.name(), e);
            }
            Arrays.sort(resources, Comparator.comparing(r -> Objects.requireNonNullElse(r.getFilename(), "")));
            if (resources.length == 0) {
                throw new IllegalStateException(loader.name() + " reads " + pattern + ", which matches no file");
            }
            for (Resource resource : resources) {
                update(digest, pattern + "/" + resource.getFilename() + "\n");
                try (InputStream in = resource.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read " + resource.getDescription(), e);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has no SHA-256", e);
        }
    }
}
