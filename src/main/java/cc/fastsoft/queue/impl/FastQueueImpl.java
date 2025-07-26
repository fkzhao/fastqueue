package cc.fastsoft.queue.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import cc.fastsoft.queue.FastArray;
import cc.fastsoft.queue.page.MappedPage;
import cc.fastsoft.queue.page.MappedPageFactory;
import cc.fastsoft.queue.page.impl.MappedPageFactoryImpl;

import cc.fastsoft.queue.FastQueue;

public class FastQueueImpl implements FastQueue {
    final FastArray innerArray;

    // 2 ^ 3 = 8
    final static int QUEUE_FRONT_INDEX_ITEM_LENGTH_BITS = 3;
    // size in bytes of queue front index page
    final static int QUEUE_FRONT_INDEX_PAGE_SIZE = 1 << QUEUE_FRONT_INDEX_ITEM_LENGTH_BITS;
    // only use the first page
    static final long QUEUE_FRONT_PAGE_INDEX = 0;

    // folder name for queue front index page
    final static String QUEUE_FRONT_INDEX_PAGE_FOLDER = "front_index";

    // front index of the big queue,
    final AtomicLong queueFrontIndex = new AtomicLong();

    // factory for queue front index page management(acquire, release, cache)
    MappedPageFactory queueFrontIndexPageFactory;

    // locks for queue front write management
    final Lock queueFrontWriteLock = new ReentrantLock();

    // lock for dequeueFuture access
    private final Object futureLock = new Object();
    private SettableFuture<byte[]> dequeueFuture;
    private SettableFuture<byte[]> peekFuture;

    /**
     * A big, fast and persistent queue implementation,
     * use default back data page size, see {@link FastArrayImpl # DEFAULT_DATA_PAGE_SIZE}
     *
     * @param queueDir  the directory to store queue data
     * @param queueName the name of the queue, will be appended as last part of the queue directory
     * @throws IOException exception throws if there is any IO error during queue initialization
     */
    public FastQueueImpl(String queueDir, String queueName) throws IOException {
        this(queueDir, queueName, FastArrayImpl.DEFAULT_DATA_PAGE_SIZE);
    }

    /**
     * A big, fast and persistent queue implementation.
     *
     * @param queueDir  the directory to store queue data
     * @param queueName the name of the queue, will be appended as last part of the queue directory
     * @param pageSize  the back data file size per page in bytes, see minimum allowed {@link FastArrayImpl#MINIMUM_DATA_PAGE_SIZE}
     * @throws IOException exception throws if there is any IO error during queue initialization
     */
    public FastQueueImpl(String queueDir, String queueName, int pageSize) throws IOException {
        innerArray = new FastArrayImpl(queueDir, queueName, pageSize);

        // the ttl does not matter here since queue front index page is always cached
        this.queueFrontIndexPageFactory = new MappedPageFactoryImpl(QUEUE_FRONT_INDEX_PAGE_SIZE,
                ((FastArrayImpl) innerArray).getArrayDirectory() + QUEUE_FRONT_INDEX_PAGE_FOLDER,
                10 * 1000/*does not matter*/);
        MappedPage queueFrontIndexPage = this.queueFrontIndexPageFactory.acquirePage(QUEUE_FRONT_PAGE_INDEX);

        ByteBuffer queueFrontIndexBuffer = queueFrontIndexPage.getLocal(0);
        long front = queueFrontIndexBuffer.getLong();
        queueFrontIndex.set(front);
    }

    @Override
    public boolean isEmpty() {
        return this.queueFrontIndex.get() == this.innerArray.getHeadIndex();
    }

    @Override
    public void enqueue(byte[] data) throws IOException {
        this.innerArray.append(data);

        this.completeFutures();
    }


    @Override
    public byte[] dequeue() throws IOException {
        long queueFrontIndex = -1L;
        try {
            queueFrontWriteLock.lock();
            if (this.isEmpty()) {
                return null;
            }
            queueFrontIndex = this.queueFrontIndex.get();
            byte[] data = this.innerArray.get(queueFrontIndex);
            long nextQueueFrontIndex = queueFrontIndex;
            if (nextQueueFrontIndex == Long.MAX_VALUE) {
                nextQueueFrontIndex = 0L; // wrap
            } else {
                nextQueueFrontIndex++;
            }
            this.queueFrontIndex.set(nextQueueFrontIndex);
            // persist the queue front
            MappedPage queueFrontIndexPage = this.queueFrontIndexPageFactory.acquirePage(QUEUE_FRONT_PAGE_INDEX);
            ByteBuffer queueFrontIndexBuffer = queueFrontIndexPage.getLocal(0);
            queueFrontIndexBuffer.putLong(nextQueueFrontIndex);
            queueFrontIndexPage.setDirty(true);
            return data;
        } finally {
            queueFrontWriteLock.unlock();
        }

    }

    @Override
    public ListenableFuture<byte[]> dequeueAsync() {
        this.initializeDequeueFutureIfNecessary();
        return dequeueFuture;
    }



    @Override
    public void removeAll() throws IOException {
        try {
            queueFrontWriteLock.lock();
            this.innerArray.removeAll();
            this.queueFrontIndex.set(0L);
            MappedPage queueFrontIndexPage = this.queueFrontIndexPageFactory.acquirePage(QUEUE_FRONT_PAGE_INDEX);
            ByteBuffer queueFrontIndexBuffer = queueFrontIndexPage.getLocal(0);
            queueFrontIndexBuffer.putLong(0L);
            queueFrontIndexPage.setDirty(true);
        } finally {
            queueFrontWriteLock.unlock();
        }
    }

    @Override
    public byte[] peek() throws IOException {
        if (this.isEmpty()) {
            return null;
        }
        byte[] data = this.innerArray.get(this.queueFrontIndex.get());
        return data;
    }

    @Override
    public ListenableFuture<byte[]> peekAsync() {
        this.initializePeekFutureIfNecessary();
        return peekFuture;
    }

    /**
     * apply an implementation of a ItemIterator interface for each queue item
     *
     */
    @Override
    public void applyForEach(ItemIterator iterator) throws IOException {
        try {
            queueFrontWriteLock.lock();
            if (this.isEmpty()) {
                return;
            }

            long index = this.queueFrontIndex.get();
            for (long i = index; i < this.innerArray.size(); i++) {
                iterator.forEach(this.innerArray.get(i));
            }
        } finally {
            queueFrontWriteLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if (this.queueFrontIndexPageFactory != null) {
            this.queueFrontIndexPageFactory.releaseCachedPages();
        }


        synchronized (futureLock) {
            /* Cancel the future but don't interrupt running tasks
            because they might perform further work not referring to the queue
             */
            if (peekFuture != null) {
                peekFuture.cancel(false);
            }
            if (dequeueFuture != null) {
                dequeueFuture.cancel(false);
            }
        }

        this.innerArray.close();
    }

    @Override
    public void gc() throws IOException {
        long beforeIndex = this.queueFrontIndex.get();
        if (beforeIndex == 0L) { // wrap
            beforeIndex = Long.MAX_VALUE;
        } else {
            beforeIndex--;
        }
        try {
            this.innerArray.removeBeforeIndex(beforeIndex);
        } catch (IndexOutOfBoundsException ex) {
            // ignore
        }
    }

    @Override
    public void flush() {
        try {
            queueFrontWriteLock.lock();
            this.queueFrontIndexPageFactory.flush();
            this.innerArray.flush();
        } finally {
            queueFrontWriteLock.unlock();
        }

    }

    @Override
    public long size() {
        long qFront = this.queueFrontIndex.get();
        long qRear = this.innerArray.getHeadIndex();
        if (qFront <= qRear) {
            return (qRear - qFront);
        } else {
            return Long.MAX_VALUE - qFront + 1 + qRear;
        }
    }


    /**
     * Completes the dequeue future
     */
    private void completeFutures() {
        synchronized (futureLock) {
            if (peekFuture != null && !peekFuture.isDone()) {
                try {
                    peekFuture.set(this.peek());
                } catch (IOException e) {
                    peekFuture.setException(e);
                }
            }
            if (dequeueFuture != null && !dequeueFuture.isDone()) {
                try {
                    dequeueFuture.set(this.dequeue());
                } catch (IOException e) {
                    dequeueFuture.setException(e);
                }
            }
        }
    }

    /**
     * Initializes the futures if it's null at the moment
     */
    private void initializeDequeueFutureIfNecessary() {
        synchronized (futureLock) {
            if (dequeueFuture == null || dequeueFuture.isDone()) {
                dequeueFuture = SettableFuture.create();
            }
            if (!this.isEmpty()) {
                try {
                    dequeueFuture.set(this.dequeue());
                } catch (IOException e) {
                    dequeueFuture.setException(e);
                }
            }
        }
    }

    /**
     * Initializes the futures if it's null at the moment
     */
    private void initializePeekFutureIfNecessary() {
        synchronized (futureLock) {
            if (peekFuture == null || peekFuture.isDone()) {
                peekFuture = SettableFuture.create();
            }
            if (!this.isEmpty()) {
                try {
                    peekFuture.set(this.peek());
                } catch (IOException e) {
                    peekFuture.setException(e);
                }
            }
        }
    }
}
