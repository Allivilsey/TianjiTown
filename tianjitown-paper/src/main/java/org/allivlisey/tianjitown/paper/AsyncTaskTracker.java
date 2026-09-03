package org.allivlisey.tianjitown.paper;

import java.time.Duration;

final class AsyncTaskTracker {
    private boolean accepting;
    private int active;

    synchronized void startAccepting() {
        if (active != 0) {
            throw new IllegalStateException("上一生命周期仍有异步任务运行");
        }
        accepting = true;
    }

    synchronized boolean begin() {
        if (!accepting) {
            return false;
        }
        active++;
        return true;
    }

    synchronized void complete() {
        if (active <= 0) {
            throw new IllegalStateException("异步任务计数不平衡");
        }
        active--;
        if (active == 0) {
            notifyAll();
        }
    }

    synchronized void stopAccepting() {
        accepting = false;
    }

    synchronized boolean awaitQuiescence(Duration timeout) {
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        while (active > 0 && remainingNanos > 0) {
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            try {
                wait(millis, nanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        return active == 0;
    }

    synchronized int active() {
        return active;
    }
}
