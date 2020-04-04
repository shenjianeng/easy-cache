package com.github.shenjianeng.easycache.core;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * @author shenjianeng
 * @date 2020/4/4
 */
public interface Cache<K, V> {

    /**
     * 根据 key 缓存中获取,缓存中不存在,则返回null
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

    void put(@NonNull K key, V value);

    void put(@NonNull Map<K, V> map);

    void evict(@NonNull K key);

    void evict(@NonNull Iterable<K> keys);

    void evictAll();
}
