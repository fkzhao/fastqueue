package cc.fastsoft.queue.page.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import cc.fastsoft.queue.page.MappedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cc.fastsoft.queue.page.MappedPageFactory;
import cc.fastsoft.queue.cache.LRUCache;
import cc.fastsoft.queue.cache.LRUCacheImpl;
import cc.fastsoft.queue.utils.FileUtil;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;


public class MappedPageFactoryImpl implements MappedPageFactory {

    private final static Logger logger = LoggerFactory.getLogger(MappedPageFactoryImpl.class);

    private final int pageSize;
    private String pageDir;
    private final File pageDirFile;
    private final String pageFile;
    private final long ttl;

    private final Object mapLock = new Object();
    private final Map<Long, Object> pageCreationLockMap = new HashMap<>();

    public static final String PAGE_FILE_NAME = "page";
    public static final String PAGE_FILE_SUFFIX = ".dat";

    private final LRUCache<Long, MappedPageImpl> cache;

    @SuppressWarnings({"ResultOfMethodCallIgnored"})
    public MappedPageFactoryImpl(int pageSize, String pageDir, long cacheTTL) {
        this.pageSize = pageSize;
        this.pageDir = pageDir;
        this.ttl = cacheTTL;
        this.pageDirFile = new File(this.pageDir);
        if (!pageDirFile.exists()) {
            pageDirFile.mkdirs();
        }
        if (!this.pageDir.endsWith(File.separator)) {
            this.pageDir += File.separator;
        }
        this.pageFile = this.pageDir + PAGE_FILE_NAME + "-";
        this.cache = new LRUCacheImpl<Long, MappedPageImpl>();
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public MappedPage acquirePage(long index) throws IOException {
        MappedPageImpl mpi = cache.get(index);
        if (mpi == null) { // not in cache, need to create one
            try {
                Object lock = null;
                synchronized(mapLock) {
                    if (!pageCreationLockMap.containsKey(index)) {
                        pageCreationLockMap.put(index, new Object());
                    }
                    lock = pageCreationLockMap.get(index);
                }
                synchronized(lock) { // only lock the creation of page index
                    mpi = cache.get(index); // double check
                    if (mpi == null) {
                        RandomAccessFile raf = null;
                        FileChannel channel = null;
                        try {
                            String fileName = this.getFileNameByIndex(index);
                            raf = new RandomAccessFile(fileName, "rw");
                            channel = raf.getChannel();
                            MappedByteBuffer mbb = channel.map(READ_WRITE, 0, this.pageSize);
                            mpi = new MappedPageImpl(mbb, fileName, index);
                            cache.put(index, mpi, ttl);
                            if (logger.isDebugEnabled()) {
                                logger.debug("Mapped page for {} was just created and cached.", fileName);
                            }
                        } finally {
                            if (channel != null) channel.close();
                            if (raf != null) raf.close();
                        }
                    }
                }
            } finally {
                synchronized(mapLock) {
                    pageCreationLockMap.remove(index);
                }
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Hit mapped page {} in cache.", mpi.getPageFile());
            }
        }

        return mpi;
    }

    private String getFileNameByIndex(long index) {
        return this.pageFile + index + PAGE_FILE_SUFFIX;
    }


    public int getPageSize() {
        return pageSize;
    }

    public String getPageDir() {
        return pageDir;
    }

    public void releasePage(long index) {
        cache.release(index);
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void releaseCachedPages() throws IOException {
        cache.removeAll();
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void deleteAllPages() throws IOException {
        cache.removeAll();
        Set<Long> indexSet = getExistingBackFileIndexSet();
        this.deletePages(indexSet);
        if (logger.isDebugEnabled()) {
            logger.debug("All page files in dir {} have been deleted.", this.pageDir);
        }
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void deletePages(Set<Long> indexes) throws IOException {
        if (indexes == null) return;
        for(long index : indexes) {
            this.deletePage(index);
        }
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void deletePage(long index) throws IOException {
        // remove the page from cache first
        cache.remove(index);
        String fileName = this.getFileNameByIndex(index);
        int count = 0;
        int maxRound = 10;
        boolean deleted = false;
        while(count < maxRound) {
            try {
                FileUtil.deleteFile(new File(fileName));
                deleted = true;
                break;
            } catch (IllegalStateException ex) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                count++;
                if (logger.isDebugEnabled()) {
                    logger.warn("fail to delete file {}, tried round = {}", fileName, count);
                }
            }
        }
        if (deleted) {
            logger.info("Page file {} was just deleted.", fileName);
        } else {
            logger.warn("fail to delete file {} after max {} rounds of try, you may delete it manually.", fileName, maxRound);
        }
    }

    @Override
    public Set<Long> getPageIndexSetBefore(long timestamp) {
        Set<Long> beforeIndexSet = new HashSet<Long>();
        File[] pageFiles = this.pageDirFile.listFiles();
        if (pageFiles != null) {
            for(File pageFile : pageFiles) {
                if (pageFile.lastModified() < timestamp) {
                    String fileName = pageFile.getName();
                    if (fileName.endsWith(PAGE_FILE_SUFFIX)) {
                        long index = this.getIndexByFileName(fileName);
                        beforeIndexSet.add(index);
                    }
                }
            }
        }
        return beforeIndexSet;
    }

    private long getIndexByFileName(String fileName) {
        int beginIndex = fileName.lastIndexOf('-');
        beginIndex += 1;
        int endIndex = fileName.lastIndexOf(PAGE_FILE_SUFFIX);
        String sIndex = fileName.substring(beginIndex, endIndex);
        return Long.parseLong(sIndex);
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void deletePagesBefore(long timestamp) throws IOException {
        Set<Long> indexSet = this.getPageIndexSetBefore(timestamp);
        this.deletePages(indexSet);
        if (logger.isDebugEnabled()) {
            logger.debug("All page files in dir [{}], before [{}] have been deleted.", this.pageDir, timestamp);
        }
    }

    @Override
    public void deletePagesBeforePageIndex(long pageIndex) throws IOException {
        Set<Long> indexSet = this.getExistingBackFileIndexSet();
        for (Long index : indexSet) {
            if (index < pageIndex) {
                this.deletePage(index);
            }
        }
    }


    @Override
    public Set<Long> getExistingBackFileIndexSet() {
        Set<Long> indexSet = new HashSet<Long>();
        File[] pageFiles = this.pageDirFile.listFiles();
        if (pageFiles != null) {
            for(File pageFile : pageFiles) {
                String fileName = pageFile.getName();
                if (fileName.endsWith(PAGE_FILE_SUFFIX)) {
                    long index = this.getIndexByFileName(fileName);
                    indexSet.add(index);
                }
            }
        }
        return indexSet;
    }

    @Override
    public int getCacheSize() {
        return cache.size();
    }

    // for testing
    int getLockMapSize() {
        return this.pageCreationLockMap.size();
    }

    @Override
    public long getPageFileLastModifiedTime(long index) {
        String pageFileName = this.getFileNameByIndex(index);
        File pageFile = new File(pageFileName);
        if (!pageFile.exists()) {
            return -1L;
        }
        return pageFile.lastModified();
    }

    @Override
    public long getFirstPageIndexBefore(long timestamp) {
        Set<Long> beforeIndexSet = getPageIndexSetBefore(timestamp);
        if (beforeIndexSet.isEmpty()) return -1L;
        TreeSet<Long> sortedIndexSet = new TreeSet<>(beforeIndexSet);
        return sortedIndexSet.last();
    }

    /**
     * thread unsafe, caller need synchronization
     */
    @Override
    public void flush() {
        Collection<MappedPageImpl> cachedPages = cache.getValues();
        for(MappedPage mappedPage : cachedPages) {
            mappedPage.flush();
        }
    }

    @Override
    public Set<String> getBackPageFileSet() {
        Set<String> fileSet = new HashSet<String>();
        File[] pageFiles = this.pageDirFile.listFiles();
        if (pageFiles != null) {
            for(File pageFile : pageFiles) {
                String fileName = pageFile.getName();
                if (fileName.endsWith(PAGE_FILE_SUFFIX)) {
                    fileSet.add(fileName);
                }
            }
        }
        return fileSet;
    }

    @Override
    public long getBackPageFileSize() {
        long totalSize = 0L;
        File[] pageFiles = this.pageDirFile.listFiles();
        if (pageFiles != null) {
            for(File pageFile : pageFiles) {
                String fileName = pageFile.getName();
                if (fileName.endsWith(PAGE_FILE_SUFFIX)) {
                    totalSize += pageFile.length();
                }
            }
        }
        return totalSize;
    }
}
