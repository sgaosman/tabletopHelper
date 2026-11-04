package com.tabletophelper.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.ContentHash;
import com.tabletophelper.content.internal.ContentLoader;
import com.tabletophelper.content.internal.ContentVersion;
import com.tabletophelper.content.internal.ContentVersionRepository;
import com.tabletophelper.content.internal.LoadContext;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the game content (D169; it replaced DataSeeder in R7). Each loader runs when the hash of its files and code
 * version differs from the one {@code content_versions} holds, or when a loader it depends on ran, in its own
 * transaction, which stores its new hash as it commits. Inside a caller's transaction, as {@code preview-content}
 * runs it, every loader joins that one instead. A loader that fails rolls back, keeps its old hash, so it runs again
 * at the next start, and the loaders that depend on it are skipped.
 */
@Service
@Slf4j
public class ContentLoading {

    // The order the loaders run in, each after the ones it depends on
    static final List<String> ORDER = List.of("conditions", "spells", "spell-effects", "items", "monsters", "races",
            "classes", "backgrounds", "feats", "optional-features", "resource-pools", "monster-actions");

    /** Why a loader would run, or null when its content is unchanged. */
    public record Planned(String loader, String reason) {
        public boolean runs() {
            return reason != null;
        }
    }

    /** What happened to one loader: it loaded, was unchanged, failed or was skipped. */
    public record Outcome(String loader, Status status, String detail, List<LoadContext.TableCounts> counts) {
        @Override
        public String toString() {
            String tables = counts.isEmpty() ? ""
                    : " (" + counts.stream().map(Object::toString).collect(Collectors.joining("; ")) + ")";
            return loader + ": " + status.name().toLowerCase() + (detail != null ? ", " + detail : "") + tables;
        }
    }

    public enum Status { LOADED, UNCHANGED, FAILED, SKIPPED }

    private final List<ContentLoader> loaders;
    private final ContentVersionRepository versions;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ContentLoading(List<ContentLoader> loaders, ContentVersionRepository versions, EntityManager entityManager,
                          ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.loaders = ordered(loaders);
        this.versions = versions;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private static List<ContentLoader> ordered(List<ContentLoader> loaders) {
        Map<String, ContentLoader> byName = new LinkedHashMap<>();
        loaders.forEach(loader -> byName.put(loader.name(), loader));
        if (!byName.keySet().equals(Set.copyOf(ORDER))) {
            throw new IllegalStateException("ContentLoading.ORDER lists " + ORDER + " but the loaders are "
                    + byName.keySet());
        }
        List<ContentLoader> ordered = ORDER.stream().map(byName::get).toList();
        Set<String> before = new HashSet<>();
        for (ContentLoader loader : ordered) {
            for (String dependency : loader.dependsOn()) {
                if (!before.contains(dependency)) {
                    throw new IllegalStateException(loader.name() + " depends on " + dependency
                            + ", which ContentLoading.ORDER doesn't run before it");
                }
            }
            before.add(loader.name());
        }
        return ordered;
    }

    /** Which loaders the next load would run, and why, without loading anything. */
    public List<Planned> plan() {
        Map<String, String> hashes = hashes();
        Map<String, ContentVersion> stored = stored();
        Set<String> running = new HashSet<>();
        List<Planned> plan = new ArrayList<>();
        for (ContentLoader loader : loaders) {
            String reason = reason(loader, hashes.get(loader.name()), stored.get(loader.name()), running);
            if (reason != null) running.add(loader.name());
            plan.add(new Planned(loader.name(), reason));
        }
        return plan;
    }

    /** Runs each loader whose content changed, and logs what each did. */
    public List<Outcome> load() {
        long start = System.currentTimeMillis();
        Map<String, String> hashes = hashes();
        Map<String, ContentVersion> stored = stored();
        Set<String> ran = new HashSet<>();
        Set<String> failed = new HashSet<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (ContentLoader loader : loaders) {
            List<String> failedDependencies = loader.dependsOn().stream().filter(failed::contains).toList();
            Outcome outcome;
            if (!failedDependencies.isEmpty()) {
                failed.add(loader.name());
                outcome = new Outcome(loader.name(), Status.SKIPPED, "because " + failedDependencies + " failed",
                        List.of());
            } else {
                String reason = reason(loader, hashes.get(loader.name()), stored.get(loader.name()), ran);
                if (reason == null) {
                    outcome = new Outcome(loader.name(), Status.UNCHANGED, null, List.of());
                } else {
                    outcome = run(loader, hashes.get(loader.name()), reason);
                    if (outcome.status() == Status.LOADED) {
                        ran.add(loader.name());
                    } else {
                        failed.add(loader.name());
                    }
                }
            }
            if (outcome.status() == Status.FAILED || outcome.status() == Status.SKIPPED) {
                log.error("Content: {}", outcome);
            } else {
                log.info("Content: {}", outcome);
            }
            outcomes.add(outcome);
        }
        log.info("Content loading complete in {} ms: {} loaded, {} unchanged, {} failed or skipped",
                System.currentTimeMillis() - start, ran.size(),
                outcomes.stream().filter(o -> o.status() == Status.UNCHANGED).count(), failed.size());
        return outcomes;
    }

    // Why the loader runs: no hash stored, a new hash, or a dependency that ran; null when none of those
    private static String reason(ContentLoader loader, String hash, ContentVersion stored, Set<String> running) {
        if (stored == null) return "no content version stored";
        if (!stored.getContentHash().equals(hash)) return "its files or code version changed";
        List<String> dependencies = loader.dependsOn().stream().filter(running::contains).toList();
        return dependencies.isEmpty() ? null : "it depends on " + String.join(", ", dependencies) + ", which ran";
    }

    // The one catch per loader: the failure is logged and the loader's transaction rolls back
    private Outcome run(ContentLoader loader, String hash, String reason) {
        long start = System.currentTimeMillis();
        try {
            List<LoadContext.TableCounts> counts = transactions.execute(status -> {
                LoadContext context = new LoadContext(objectMapper);
                try {
                    loader.load(context);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                ContentVersion version = versions.findById(loader.name()).orElseGet(ContentVersion::new);
                version.setLoader(loader.name());
                version.setContentHash(hash);
                version.setLoaderVersion(loader.version());
                version.setRowCount(context.rowCount());
                version.setLoadedAt(Instant.now());
                versions.save(version);
                entityManager.flush();
                entityManager.clear();
                return context.counts();
            });
            return new Outcome(loader.name(), Status.LOADED,
                    "because " + reason + ", in " + (System.currentTimeMillis() - start) + " ms", counts);
        } catch (RuntimeException e) {
            log.error("Content: {} failed and rolled back: {}", loader.name(), e.getMessage(), e);
            return new Outcome(loader.name(), Status.FAILED, e.getMessage(), List.of());
        }
    }

    private Map<String, String> hashes() {
        Map<String, String> hashes = new HashMap<>();
        loaders.forEach(loader -> hashes.put(loader.name(), ContentHash.of(loader)));
        return hashes;
    }

    private Map<String, ContentVersion> stored() {
        Map<String, ContentVersion> stored = new HashMap<>();
        versions.findAll().forEach(version -> stored.put(version.getLoader(), version));
        return stored;
    }
}
