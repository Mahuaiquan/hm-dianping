package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeout
     * @return
     */
    boolean tryLock(long timeout);

    /**
     * 释放锁
     */
    void unlock();
}
