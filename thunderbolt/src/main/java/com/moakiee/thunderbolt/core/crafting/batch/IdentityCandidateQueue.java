package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Identity-based active candidate queue with O(1) permanent removal and success affinity. */
final class IdentityCandidateQueue<T> implements Iterable<T> {
    private final IdentityHashMap<T, Node<T>> active = new IdentityHashMap<>();
    private final IdentityHashMap<T, Boolean> blocked = new IdentityHashMap<>();
    private Node<T> head;
    private Node<T> tail;
    private T preferred;

    IdentityCandidateQueue(Iterable<? extends T> candidates) {
        for (var candidate : candidates) {
            if (candidate == null || active.containsKey(candidate)) continue;
            var node = new Node<>(candidate);
            active.put(candidate, node);
            if (tail == null) {
                head = tail = node;
            } else {
                tail.next = node;
                node.previous = tail;
                tail = node;
            }
        }
    }

    void block(T candidate) {
        blocked.put(candidate, Boolean.TRUE);
        var node = active.remove(candidate);
        if (node != null) {
            if (node.previous != null) node.previous.next = node.next;
            else head = node.next;
            if (node.next != null) node.next.previous = node.previous;
            else tail = node.previous;
            // Existing iterators use the removed node's old next link to advance once, then reject
            // it through active membership. Do not sever the links here.
        }
        if (preferred == candidate) preferred = null;
    }

    void markSuccess(T candidate) {
        if (active.containsKey(candidate)) preferred = candidate;
    }

    boolean isBlocked(T candidate) {
        return blocked.containsKey(candidate);
    }

    int activeCount() {
        return active.size();
    }

    int blockedCount() {
        return blocked.size();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private final T preferredFirst = preferred;
            private boolean preferredVisited;
            private Node<T> cursor = head;
            private T next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (!preferredVisited) {
                        preferredVisited = true;
                        if (preferredFirst != null && active.containsKey(preferredFirst)) {
                            next = preferredFirst;
                            break;
                        }
                    }
                    if (cursor == null) break;
                    var candidate = cursor.value;
                    cursor = cursor.next;
                    if (candidate != preferredFirst && active.containsKey(candidate)) {
                        next = candidate;
                    }
                }
                return next != null;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                var result = next;
                next = null;
                return result;
            }
        };
    }

    private static final class Node<T> {
        private final T value;
        private Node<T> previous;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }
}
