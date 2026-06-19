package org.slate.memtable;

import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

public class SkipList<K,V> {
    //2^32 ~ 4Bn we can have fewer based on our compaction threshold
    private static final int MAX_LEVEL = 32;
    //leads to a possible max of 2 levels where each node can be. (summation of 1/2^n)
    private static final double PROBABILITY = 0.5;

    private final Comparator<K> comparator;
    private final Node<K, V> head;
    private int level = 1;
    private int size = 0;

    public SkipList(Comparator<K> comparator) {
        this.comparator = comparator;
        // head is a sentinel node — holds no real key/value
        this.head = new Node<>(null, null, MAX_LEVEL);
    }

    // ==== Node ============

    public static class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level];
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
    }


    // ==== Random level generation ============
    // P(level >= k) = PROBABILITY^(k-1)
    //there is a 0.5 chance that the lvl will be incremented or it will stop incrementing
    //which means at each put op we get a  0.5 chance of getting to lvl1 (there is a 1/1 chance of getting to lvl0)
    // 0.25 chance of getting to lvl 2 and a 0.125 chance of getting to lvl 3 ....
    //this helps in ensuring the pyramid structure and tree like search capabilities
    private int randomLevel() {
        int lvl = 1;
        while (ThreadLocalRandom.current().nextDouble() < PROBABILITY
                && lvl < MAX_LEVEL) {
            lvl++;
        }
        return lvl;
    }

    // === PUT =========
    public V put(K key,V value){
        Node<K,V> current = head;
        //we use this array to find all the nodes lesser than our current node at each level
        //later we weave our node at each level
        Node<K,V>[] update = new Node[MAX_LEVEL];

        for(int i=level-1;i>=0;i--){
            while(current.next[i]!=null &&
                comparator.compare(current.next[i].key, key) < 0){
                //traverse the same level until the next node is greater than our node
                current = current.next[i];
            }
            update[i] = current;
        }

        //check if key already exists at lvl0 and update value
        // key already exists — overwrite value, no new node
        Node<K, V> next = update[0].next[0];
        if (next != null && comparator.compare(next.key, key) == 0) {
            V oldValue = next.value;
            next.value = value;
            return oldValue;
        }

        //we are getting to new levels that dont have any head pointers to them
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }

        //weave the node at appropriate points
        Node<K,V> newNode = new Node<>(key,value,newLevel);
        for(int i=0;i<newLevel;i++){
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }
        size++;
        return null;
    }

    // === Get ======
    public V get(K key) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && comparator.compare(current.next[i].key, key) < 0) {
                current = current.next[i];
            }
        }
        Node<K, V> candidate = current.next[0];
        if (candidate != null && comparator.compare(candidate.key, key) == 0) {
            return candidate.value;
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    // === Remove (physical removal — distinct from tombstone) =====
    // Used internally; MemTable handles logical delete via tombstones.

    public boolean remove(K key) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> current = head;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && comparator.compare(current.next[i].key, key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        //we search at 0 level since that is the level
        // where all the nodes are present
        Node<K, V> target = update[0].next[0];
        if (target == null || comparator.compare(target.key, key) != 0) {
            return false;
        }

        //update for each level where the node is present
        for (int i = 0; i < level; i++) {
            if (update[i].next[i] != target) break;
            update[i].next[i] = target.next[i];
        }

        //if any level becomes empty we reduce the total levels
        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }
        size--;
        return true;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    //==== Sorted forward iteration ========

    public Iterator<Node<K, V>> iterator() {
        return new Iterator<>() {
            Node<K, V> current = head.next[0];

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public Node<K, V> next() {
                if (current == null) throw new NoSuchElementException();
                Node<K, V> result = current;
                current = current.next[0];
                return result;
            }
        };
    }

}
