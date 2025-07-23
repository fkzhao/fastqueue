package dev.fastqueue;
import dev.fastqueue.impl.FastQueueImpl;
import org.junit.Test;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FastQueueUnitTest {
    private String testDir = TestUtil.TEST_BASE_DIR + "fastqueue/unit";
    private FastQueue fastQueue;

    @Test
    public void simpleTest() throws IOException {
        for(int i = 1; i <= 2; i++) {

            fastQueue = new FastQueueImpl(testDir, "simple_test");
            assertNotNull(fastQueue);

            for(int j = 1; j <= 3; j++) {
                assertTrue(fastQueue.isEmpty());
                assertTrue(fastQueue.isEmpty());

                assertNull(fastQueue.dequeue());
                assertNull(fastQueue.peek());


                fastQueue.enqueue("hello".getBytes());
                assertEquals(1L, fastQueue.size());
                assertFalse(fastQueue.isEmpty());
                assertEquals("hello", new String(fastQueue.peek()));
                assertEquals("hello", new String(fastQueue.dequeue()));
                assertNull(fastQueue.dequeue());

                fastQueue.enqueue("world".getBytes());
                fastQueue.flush();
                assertEquals(1L, fastQueue.size());
                assertFalse(fastQueue.isEmpty());
                assertEquals("world", new String(fastQueue.dequeue()));
                assertNull(fastQueue.dequeue());

            }

            fastQueue.close();

        }
    }
}
