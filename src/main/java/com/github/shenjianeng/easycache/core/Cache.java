package com.github.shenjianeng.easycache.core;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * @author shenjianeng
 * @date 2020/4/4
 */
public interface Cache<K, V> {

    /**
     * 根据 keys 缓存中获取,缓存中不存在,则返回null
     */
    @NonNull
    Map<K, V> getIfPresent(@NonNull Iterable<K> keys);

    /**
     * 根据 key 缓存中获取,缓存中不存在,则返回null
     */
    @Nullable
    V getIfPresent(@NonNull K key);

    /**
     * 根据 key 从缓存中获取,如果缓存中不存在,调用 {@link MultiCacheLoader#loadCache(java.util.Collection)} 加载数据,并添加到缓存中
     */
    @NonNull
    Map<K, V> getOrLoadIfAbsent(@NonNull Iterable<K> keys);

    /**
     * 根据 key 从缓存中获取
     * 如果缓存中不存在,调用 {@link MultiCacheLoader#loadCache(java.util.Collection)} 加载数据,并添加到缓存中
     */
    @Nullable
    V getOrLoadIfAbsent(@NonNull K key);

    /**
     * 加入缓存中
     */
    void put(@NonNull K key, V value);

    /**
     * 加入缓存中
     */
    void put(@NonNull Map<K, V> map);

    /**
     * 根据 key 清除缓存
     */
    void evict(@NonNull K key);

    /**
     * 根据 keys 清除缓存
     */
    void evict(@NonNull Iterable<K> keys);

    /**
     * 清除所有缓存
     */
    void evictAll();
}
