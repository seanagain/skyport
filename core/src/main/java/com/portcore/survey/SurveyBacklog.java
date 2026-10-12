package com.portcore.survey;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What is still waiting to be surveyed, and how much of it may happen now.
 *
 * Installed on a server that has been running for a year, this has a backlog
 * of every chunk anybody has ever generated - hundreds of thousands of them.
 * Surveying that eagerly would stall the server at the exact moment somebody
 * added the mod and decided what they thought of it.
 *
 * So the backlog is drained a few at a time, forever, and the discipline is
 * all here rather than spread through the surveyor:
 *
 * Work is DEDUPED, because the same chunk arrives from several directions -
 * a region scan, a chunk load, a player wandering back - and surveying it
 * three times costs three disk reads for one answer.
 *
 * Work is PRIORITISED, because a chunk somebody is standing in matters more
 * than one in a region file from a year ago. Urgent work jumps the queue
 * rather than joining it.
 *
 * The backlog is BOUNDED. An unbounded queue fed by region enumeration is a
 * memory leak with a respectable name, and dropping the oldest unurgent work
 * is harmless: it was going to be surveyed eventually, and it still will be
 * the next time anything asks about it.
 *
 * Nothing here knows what a chunk is. It queues longs, which is what makes
 * it testable without a world.
 */
public final class SurveyBacklog {

    /**
     * Chosen so a full backlog is a few megabytes rather than a few hundred:
     * at eight bytes a key this is about a megabyte of queue, and anything
     * dropped comes back the moment something asks for it.
     */
    public static final int DEFAULT_CAPACITY = 131_072;

    private final int capacity;
    private final Deque<Long> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private long dropped;

    public SurveyBacklog() {
        this(DEFAULT_CAPACITY);
    }

    public SurveyBacklog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Add work to the back of the queue.
     *
     * @return false if it was already waiting, or the backlog is full
     */
    public boolean offer(long key) {
        if (queued.contains(key)) return false;
        if (queue.size() >= capacity) {
            dropped++;
            return false;
        }
        queue.addLast(key);
        queued.add(key);
        return true;
    }

    /**
     * Add work at the front, ahead of everything already waiting.
     *
     * For chunks somebody is actually near. A player sailing into unsurveyed
     * water should not wait behind a region file from last year, and without
     * this they would: the backlog is drained in order and the order is a
     * year old.
     *
     * Already-queued work is promoted rather than duplicated.
     */
    public boolean offerUrgent(long key) {
        if (queued.contains(key)) {
            queue.remove(key);
        } else if (queue.size() >= capacity) {
            // Make room: urgent work is worth more than the oldest ordinary
            // work, which will be re-offered the next time anything asks.
            Long evicted = queue.pollLast();
            if (evicted != null) {
                queued.remove(evicted);
                dropped++;
            }
        }
        queue.addFirst(key);
        queued.add(key);
        return true;
    }

    /** Take up to {@code budget} items, which stop being queued. */
    public List<Long> take(int budget) {
        if (budget <= 0 || queue.isEmpty()) return List.of();
        List<Long> out = new java.util.ArrayList<>(Math.min(budget, queue.size()));
        for (int i = 0; i < budget; i++) {
            Long next = queue.pollFirst();
            if (next == null) break;
            queued.remove(next);
            out.add(next);
        }
        return out;
    }

    public boolean isWaiting(long key) {
        return queued.contains(key);
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** How much work was turned away for want of room - worth a line in a
     *  diagnostic, because a backlog that is permanently full means the
     *  survey rate is set too low for the world it is in. */
    public long droppedCount() {
        return dropped;
    }

    public void clear() {
        queue.clear();
        queued.clear();
    }
}
