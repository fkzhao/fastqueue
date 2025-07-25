package cc.fastsoft.queue.cache;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LRUCacheImpl <K, V extends Closeable> implements LRUCache<K, V>{

    private final static Logger logger = LoggerFactory.getLogger(LRUCacheImpl.class);

    public static final long DEFAULT_TTL = 10 * 1000; // milliseconds

    private final Map<K, V> map;
    private final Map<K, TTLValue> ttlMap;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Set<K> keysToRemove = new HashSet<>();

    public LRUCacheImpl() {
        map = new HashMap<>();
        ttlMap = new HashMap<>();
    }

    /**
     * Shutdown the internal ExecutorService,
     * Call this only after you have closed your fast queue instance.
     */
    public static void CloseExecutorService() {
        executorService.shutdown();
    }

    public void put(K key, V value, long ttlInMilliSeconds) {
        Collection<V> valuesToClose;
        try {
            writeLock.lock();
            // trigger mark&sweep
            valuesToClose = markAndSweep();
            if (valuesToClose != null) { // just be cautious
                valuesToClose.remove(value);
            }
            map.put(key, value);
            TTLValue ttl = new TTLValue(System.currentTimeMillis(), ttlInMilliSeconds);
            ttl.refCount.incrementAndGet();
            ttlMap.put(key, ttl);
        } finally {
            writeLock.unlock();
        }
        if (valuesToClose != null && !valuesToClose.isEmpty()) {
            if (logger.isDebugEnabled()) {
                int size = valuesToClose.size();
                logger.info("Mark&Sweep found {}{} to close.", size, size > 1 ? " resources" : " resource");
            }
            // close resource asynchronously
            executorService.execute(new ValueCloser<>(valuesToClose));
        }
    }

    public void put(K key, V value) {
        this.put(key, value, DEFAULT_TTL);
    }

    /**
     * A lazy mark and sweep,
     * a separate thread can also do this.
     */
    private Collection<V> markAndSweep() {
        Collection<V> valuesToClose = null;
        keysToRemove.clear();
        Set<K> keys = ttlMap.keySet();
        long currentTS = System.currentTimeMillis();
        for(K key: keys) {
            TTLValue ttl = ttlMap.get(key);
            if (ttl.refCount.get() <= 0 && (currentTS - ttl.lastAccessedTimestamp.get()) > ttl.ttl) { // remove object with no reference and expired
                keysToRemove.add(key);
            }
        }

        if (!keysToRemove.isEmpty()) {
            valuesToClose = new HashSet<>();
            for(K key : keysToRemove) {
                V v = map.remove(key);
                valuesToClose.add(v);
                ttlMap.remove(key);
            }
        }

        return valuesToClose;
    }

    public V get(K key) {
        try {
            readLock.lock();
            TTLValue ttl = ttlMap.get(key);
            if (ttl != null) {
                // Since the resource is acquired by calling thread,
                // let's update last accessed timestamp and increment reference counting
                ttl.lastAccessedTimestamp.set(System.currentTimeMillis());
                ttl.refCount.incrementAndGet();
            }
            return map.get(key);
        } finally {
            readLock.unlock();
        }
    }

    private static class TTLValue {
        AtomicLong lastAccessedTimestamp; // last accessed time
        AtomicLong refCount = new AtomicLong(0);
        long ttl;

        public TTLValue(long ts, long ttl) {
            this.lastAccessedTimestamp = new AtomicLong(ts);
            this.ttl = ttl;
        }
    }

    private static class ValueCloser<V extends Closeable> implements Runnable {
        Collection<V> valuesToClose;

        public ValueCloser(Collection<V> valuesToClose) {
            this.valuesToClose = valuesToClose;
        }

        public void run() {
            int size = valuesToClose.size();
            for(V v : valuesToClose) {
                try {
                    if (v != null) {
                        v.close();
                    }
                } catch (IOException e) {
                    // close quietly
                }
            }
            if (logger.isDebugEnabled()) {
                logger.debug("ResourceCloser closed {}{}", size, size > 1 ? " resources." : " resource.");
            }
        }
    }

    public void release(K key) {
        try {
            readLock.lock();
            TTLValue ttl = ttlMap.get(key);
            if (ttl != null) {
                // since the resource is released by calling thread
                // let's decrement the reference counting
                ttl.refCount.decrementAndGet();
            }
        } finally {
            readLock.unlock();
        }
    }

    public int size() {
        try {
            readLock.lock();
            return map.size();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void removeAll()  throws IOException {
        try {
            writeLock.lock();

            Collection<V> valuesToClose = new HashSet<>(map.values());

            if (!valuesToClose.isEmpty()) {
                // close resource synchronously
                for(V v : valuesToClose) {
                    v.close();
                }
            }
            map.clear();
            ttlMap.clear();

        } finally {
            writeLock.unlock();
        }

    }

    @Override
    public V remove(K key) throws IOException {
        try {
            writeLock.lock();
            ttlMap.remove(key);
            V value = map.remove(key);
            if (value != null) {
                // close synchronously
                value.close();
            }
            return value;
        } finally {
            writeLock.unlock();
        }

    }

    @Override
    public Collection<V> getValues() {
        try {
            readLock.lock();
            return new ArrayList<>(map.values());
        } finally {
            readLock.unlock();
        }
    }

}
