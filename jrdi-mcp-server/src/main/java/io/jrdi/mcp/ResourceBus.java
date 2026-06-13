package io.jrdi.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe pub-sub registry for MCP resources.
 *
 * <p>When a client calls {@code resources/subscribe} with a URI like
 * {@code jrdi://stats}, the MCP server registers a notifier — a
 * {@link Consumer} that knows how to push a {@code resources/updated}
 * notification to the client's transport (stdio OutputStream, HTTP SSE
 * response, etc.). When something changes the resource
 * (e.g. an index run completes), the bus calls all subscribers.
 *
 * <p>One bus per {@link JrdiMcpServer} instance. Per-connection transports
 * register their notifier when a client subscribes; when the connection
 * closes, the notifier is removed via {@link #unsubscribe}.
 *
 * <p>The bus is a plain in-memory pub-sub. There's no persistence: a
 * subscription is lost when the server restarts. The MCP spec calls this
 * out — the client should re-subscribe on every reconnect.
 */
public final class ResourceBus {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceBus.class);

    /** URI -> set of subscriber notifiers. We use plain HashSet inside
     * ConcurrentHashMap (which provides safe concurrent map operations) and
     * synchronize on the set for subscribe/unsubscribe. Reads (publish) take
     * a snapshot via the iterator, so the publisher doesn't see concurrent
     * modification. For typical sizes (1-3 subscribers per URI) this is
     * faster than CopyOnWriteArraySet (which doesn't support iterator.remove()). */
    private final ConcurrentHashMap<String, Set<Consumer<String>>> subscribers
            = new ConcurrentHashMap<>();

    /**
     * Register a notifier. The notifier is invoked (in the publisher's thread)
     * with the URI whenever {@link #publish(String)} is called for that URI.
     * Returns a token that can be passed to {@link #unsubscribe} to remove
     * the subscription.
     */
    public Subscription subscribe(String uri, Consumer<String> notifier) {
        Set<Consumer<String>> set = subscribers.computeIfAbsent(uri, k -> new HashSet<>());
        synchronized (set) {
            set.add(notifier);
        }
        LOG.debug("subscribed to {} (now {} subscribers)", uri, count(uri));
        return new Subscription(uri, notifier);
    }

    /**
     * Remove a previously-registered notifier. Idempotent.
     */
    public void unsubscribe(Subscription sub) {
        if (sub == null) return;
        Set<Consumer<String>> set = subscribers.get(sub.uri);
        if (set != null) {
            synchronized (set) {
                set.remove(sub.notifier);
            }
        }
        LOG.debug("unsubscribed from {} (now {} subscribers)", sub.uri,
                set == null ? 0 : set.size());
    }

    /**
     * Remove the first notifier for the given URI. Returns 1 if a notifier
     * was removed, 0 otherwise. Used by the {@code resources/unsubscribe}
     * handler when the client doesn't supply a token.
     */
    public int removeFirstSubscriber(String uri) {
        Set<Consumer<String>> set = subscribers.get(uri);
        if (set == null) return 0;
        synchronized (set) {
            if (set.isEmpty()) return 0;
            var it = set.iterator();
            it.next();
            it.remove();
        }
        LOG.debug("removed first subscriber for {} (now {} left)", uri,
                set.size());
        return 1;
    }

    /**
     * Notify all subscribers of {@code uri}. The notification is delivered
     * synchronously, in the publisher's thread. We take a snapshot of the
     * subscriber set under the lock to avoid ConcurrentModificationException
     * if a notifier is added/removed during iteration.
     */
    public int publish(String uri) {
        Set<Consumer<String>> set = subscribers.get(uri);
        if (set == null) return 0;
        // Snapshot under lock so publish doesn't see concurrent modification.
        java.util.List<Consumer<String>> snapshot;
        synchronized (set) {
            snapshot = new java.util.ArrayList<>(set);
        }
        int n = 0;
        for (Consumer<String> notifier : snapshot) {
            try {
                notifier.accept(uri);
                n++;
            } catch (Exception e) {
                // A faulty notifier must not break the publisher.
                LOG.warn("subscriber for {} failed: {}", uri, e.getMessage());
            }
        }
        return n;
    }

    /** How many notifiers are currently subscribed to the given URI. */
    public int count(String uri) {
        Set<Consumer<String>> set = subscribers.get(uri);
        return set == null ? 0 : set.size();
    }

    /** Sum of all subscriptions across all URIs. Useful for /shutdown cleanup. */
    public int totalSubscriptions() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    /** Opaque token returned by {@link #subscribe} for later unsubscribe. */
    public static final class Subscription {
        final String uri;
        final Consumer<String> notifier;
        Subscription(String uri, Consumer<String> notifier) {
            this.uri = uri;
            this.notifier = notifier;
        }
    }
}
