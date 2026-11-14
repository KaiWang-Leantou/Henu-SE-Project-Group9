package com.henu.whiteboard.room;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每房间一个的单线程执行器，用于把同一 board 的操作串行化并保证 seq 顺序。
 */
public class SerialExecutor implements AutoCloseable {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SerialExecutor(String name) {
        Objects.requireNonNull(name, "name");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "wb-room-" + name + "-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(factory);
    }

    /** 提交带返回值的任务，调用方可持有 Future 回收结果。 */
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return worker.submit(task);
    }

    /** 提交无返回值任务；不使用 submit(Runnable) 以避免与 Callable 重载产生歧义。 */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        worker.execute(task);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdown();
        }
    }
}
