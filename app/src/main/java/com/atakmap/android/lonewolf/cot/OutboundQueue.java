package com.atakmap.android.lonewolf.cot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Ordered queue of CoT messages waiting for a TAK Server connection.
 * Pure Java; persistence is handled by the caller.
 * <p>
 * Messages carry a collapse key. Adding a message removes any older queued
 * message with the same key and appends the new one at the end, so only the
 * newest status / emergency update is kept. Different keys keep their relative
 * order, so an emergency followed by its cancel is delivered in that order.
 */
public class OutboundQueue {

    public static final String KEY_STATUS = "status";
    public static final String KEY_EMERGENCY = "emergency";
    public static final String KEY_CANCEL = "cancel";

    public static class Entry {
        public final String key;
        public final String xml;
        public final long queuedAt;

        public Entry(String key, String xml, long queuedAt) {
            this.key = key;
            this.xml = xml;
            this.queuedAt = queuedAt;
        }
    }

    private final int maxSize;
    private final LinkedList<Entry> entries = new LinkedList<>();

    public OutboundQueue(int maxSize) {
        this.maxSize = maxSize;
    }

    public synchronized void add(String key, String xml, long now) {
        if (key != null) {
            Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                if (key.equals(it.next().key))
                    it.remove();
            }
        }
        entries.addLast(new Entry(key, xml, now));
        while (entries.size() > maxSize) {
            // Never drop emergencies or cancels; drop the oldest other entry.
            boolean removed = false;
            Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (!KEY_EMERGENCY.equals(e.key) && !KEY_CANCEL.equals(e.key)) {
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed)
                entries.removeFirst();
        }
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Remove and return everything, oldest first. */
    public synchronized List<Entry> drain() {
        List<Entry> out = new ArrayList<>(entries);
        entries.clear();
        return out;
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void restore(List<Entry> list) {
        entries.clear();
        entries.addAll(list);
    }
}
