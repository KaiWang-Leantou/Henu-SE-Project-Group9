package com.henu.whiteboard.room;

import com.henu.whiteboard.board.BoardDoc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间串行执行器测试：多线程并发提交 100 个生产 seq 的任务，验证无重复且连续。
 */
class RoomSeqTest {

    @Test
    void seqIsContiguousUnderConcurrentSubmit() throws Exception {
        Room room = new Room("b_seq", BoardDoc.create("b_seq"));
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> seqFutures = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> submitterFutures = new ArrayList<>();

            for (int thread = 0; thread < 4; thread++) {
                submitterFutures.add(submitters.submit(() -> {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                        seqFutures.add(room.executor().submit(room::nextSeq));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> submitter : submitterFutures) {
                submitter.get(5, TimeUnit.SECONDS);
            }

            Set<Long> seqs = new HashSet<>();
            for (Future<Long> future : seqFutures) {
                seqs.add(future.get(5, TimeUnit.SECONDS));
            }

            assertEquals(100, seqs.size(), "seq 不应重复");
            for (long expected = 1; expected <= 100; expected++) {
                assertTrue(seqs.contains(expected), "缺少 seq " + expected);
            }
            assertEquals(100L, room.seq());
        } finally {
            submitters.shutdownNow();
            room.close();
        }
    }
}
