package com.github.lechandonga.monoreporeleasetrainjava.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地故障注入：让某个包的前 N 次发布尝试失败，用于验证中途失败与重试语义。
 * 仅用于本地测试，不依赖任何外部服务。
 */
@Component
public class FailureInjector {

    private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();

    private volatile String blockedPackage;
    private volatile CountDownLatch releaseLatch;
    private volatile CountDownLatch enteredLatch;

    public void failNextAttempts(String packageName, int times) {
        remainingFailures.put(packageName, new AtomicInteger(times));
    }

    public void clear(String packageName) {
        remainingFailures.remove(packageName);
    }

    public void clearAll() {
        remainingFailures.clear();
        clearBlock();
    }

    /**
     * 让指定包的发布在真正落盘前阻塞，直到 releaseBlock() 被调用，
     * 为并发测试提供确定性的时间窗口。
     */
    public void installBlock(String packageName) {
        this.blockedPackage = packageName;
        this.releaseLatch = new CountDownLatch(1);
        this.enteredLatch = new CountDownLatch(1);
    }

    public void awaitBlockedEntered() {
        try {
            if (enteredLatch != null) {
                enteredLatch.await(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public void releaseBlock() {
        if (releaseLatch != null) {
            releaseLatch.countDown();
        }
    }

    public void clearBlock() {
        blockedPackage = null;
        releaseLatch = null;
        enteredLatch = null;
    }

    void maybeBlock(String packageName) {
        if (blockedPackage != null && blockedPackage.equals(packageName) && releaseLatch != null) {
            enteredLatch.countDown();
            try {
                releaseLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean shouldFail(String packageName) {
        AtomicInteger counter = remainingFailures.get(packageName);
        return counter != null && counter.getAndDecrement() > 0;
    }
}
