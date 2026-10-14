package io.github.viewerparrots.registry;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Immutable AVL nodes let a snapshot retain its generation with O(1) root capture. */
final class PersistentSnapshotIndex<K extends Comparable<K>, V> {
    private record Node<K, V>(K key, V value, Node<K, V> left, Node<K, V> right, int height) {}
    private Node<K, V> root;

    void put(K key, V value) { root = put(root, key, value); }
    void remove(K key) { root = remove(root, key); }
    void clear() { root = null; }

    Iterator<V> iterator() {
        return new Iterator<>() {
            private final ArrayDeque<Node<K, V>> stack = new ArrayDeque<>();
            { pushLeft(root); }
            private void pushLeft(Node<K, V> node) {
                while (node != null) { stack.push(node); node = node.left(); }
            }
            public boolean hasNext() { return !stack.isEmpty(); }
            public V next() {
                if (stack.isEmpty()) throw new NoSuchElementException();
                Node<K, V> node = stack.pop();
                pushLeft(node.right());
                return node.value();
            }
        };
    }

    private Node<K, V> put(Node<K, V> node, K key, V value) {
        if (node == null) return node(key, value, null, null);
        int comparison = key.compareTo(node.key());
        if (comparison < 0) return balance(node(node.key(), node.value(), put(node.left(), key, value), node.right()));
        if (comparison > 0) return balance(node(node.key(), node.value(), node.left(), put(node.right(), key, value)));
        return node(key, value, node.left(), node.right());
    }

    private Node<K, V> remove(Node<K, V> node, K key) {
        if (node == null) return null;
        int comparison = key.compareTo(node.key());
        if (comparison < 0) return balance(node(node.key(), node.value(), remove(node.left(), key), node.right()));
        if (comparison > 0) return balance(node(node.key(), node.value(), node.left(), remove(node.right(), key)));
        if (node.left() == null) return node.right();
        if (node.right() == null) return node.left();
        Node<K, V> successor = node.right();
        while (successor.left() != null) successor = successor.left();
        return balance(node(successor.key(), successor.value(), node.left(), remove(node.right(), successor.key())));
    }

    private Node<K, V> balance(Node<K, V> node) {
        int difference = height(node.left()) - height(node.right());
        if (difference > 1) {
            Node<K, V> left = node.left();
            if (height(left.left()) < height(left.right()))
                node = node(node.key(), node.value(), rotateLeft(left), node.right());
            return rotateRight(node);
        }
        if (difference < -1) {
            Node<K, V> right = node.right();
            if (height(right.right()) < height(right.left()))
                node = node(node.key(), node.value(), node.left(), rotateRight(right));
            return rotateLeft(node);
        }
        return node;
    }

    private Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right();
        return node(pivot.key(), pivot.value(), node(node.key(), node.value(), node.left(), pivot.left()), pivot.right());
    }
    private Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left();
        return node(pivot.key(), pivot.value(), pivot.left(), node(node.key(), node.value(), pivot.right(), node.right()));
    }
    private Node<K, V> node(K key, V value, Node<K, V> left, Node<K, V> right) {
        return new Node<>(key, value, left, right, 1 + Math.max(height(left), height(right)));
    }
    private int height(Node<K, V> node) { return node == null ? 0 : node.height(); }
}
