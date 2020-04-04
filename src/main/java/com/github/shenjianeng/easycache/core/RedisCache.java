package com.github.shenjianeng.easycache.core;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author shenjianeng
 * @date 2020/4/4
 */
@SuppressWarnings("all")
public class RedisCache<K extends Serializable, V extends Serializable> implements Cache<K, V> {

    private static final int MAX_BATCH_KEY_SIZE = 20;
    private static final String KNOWN_KEYS_NAME_SUFFIX = "$$knownKeys$$";

    private final String knownKeysName;

    private final RedisTemplate<String, Serializable> redisTemplate;

    private final Duration timeToLive;

    private final String keyPrefix;

    private final MultiCacheLoader<K, V> multiCacheLoader;

    private final RedisKeyGenerator<K> keyGenerator;


    public RedisCache(String keyPrefix, RedisTemplate<String, Serializable> redisTemplate,
                      Duration timeToLive, MultiCacheLoader<K, V> multiCacheLoader) {

        this(keyPrefix, redisTemplate, timeToLive, multiCacheLoader, DefaultRedisKeyGenerator.INSTANCE);
    }

    public RedisCache(String keyPrefix, RedisTemplate<String, Serializable> redisTemplate,
                      Duration timeToLive, MultiCacheLoader<K, V> multiCacheLoader,
                      RedisKeyGenerator<K> keyGenerator) {

        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.timeToLive = Objects.requireNonNull(timeToLive);
        this.keyPrefix = Objects.requireNonNull(keyPrefix);
        this.multiCacheLoader = Objects.requireNonNull(multiCacheLoader);
        this.keyGenerator = Objects.requireNonNull(keyGenerator);
        this.knownKeysName = keyPrefix + KNOWN_KEYS_NAME_SUFFIX;

    }


    @Override
    @NonNull
    public Map<K, V> getIfPresent(@NonNull Iterable<K> keys) {
        return doGetOrLoadIfAbsent(keys, false);
    }


    @Override
    @Nullable
    public V getIfPresent(@NonNull K key) {
        return doGetOrLoadIfAbsent(key, false);
    }

    @Override
    @NonNull
    public Map<K, V> getOrLoadIfAbsent(@NonNull Iterable<K> keys) {
        return doGetOrLoadIfAbsent(keys, true);
    }

    @Override
    @Nullable
    public V getOrLoadIfAbsent(@NonNull K key) {
        return doGetOrLoadIfAbsent(key, true);
    }


    @Override
    public void put(@NonNull K key, V value) {
        put(ImmutableMap.of(key, value));
    }


    @Override
    public void put(@NonNull Map<K, V> map) {
        RedisSerializer<Serializable> keySerializer = (RedisSerializer<Serializable>) redisTemplate.getKeySerializer();
        RedisSerializer<Serializable> valueSerializer = (RedisSerializer<Serializable>) redisTemplate.getValueSerializer();

        byte[] knownKeysNameBytes = keySerializer.serialize(knownKeysName);

        redisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                map.forEach((k, v) -> {
                    if (v != null && k != null) {
                        // todo support null value
                        String cacheKey = buildCacheKey(k);
                        connection.set(keySerializer.serialize(cacheKey), valueSerializer.serialize(v),
                                Expiration.from(timeToLive.getSeconds() + ThreadLocalRandom.current().nextInt(60), TimeUnit.SECONDS),
                                RedisStringCommands.SetOption.UPSERT);
                        // maintain KnownKeys
                        connection.zAdd(knownKeysNameBytes, 0, valueSerializer.serialize(cacheKey));
                    }
                });

                connection.expire(knownKeysNameBytes, timeToLive.getSeconds());
                return null;
            }
        });
    }

    @Override
    public void evict(@NonNull K key) {
        String cacheKey = buildCacheKey(key);
        redisTemplate.delete(cacheKey);
        redisTemplate.opsForZSet().remove(knownKeysName, cacheKey);
    }

    @Override
    public void evict(@NonNull Iterable<K> keys) {
        List<String> cacheKeyList = buildCacheKey(keys);

        redisTemplate.delete(cacheKeyList);
        redisTemplate.opsForZSet().remove(knownKeysName, cacheKeyList);

    }

    @Override
    public void evictAll() {
        Set<Serializable> serializables = redisTemplate.opsForZSet().rangeByScore(knownKeysName, 0, 0);

        if (!CollectionUtils.isEmpty(serializables)) {
            List<String> cacheKeys = Lists.newArrayListWithExpectedSize(serializables.size());
            serializables.forEach(serializable -> {
                if (serializable instanceof String) {
                    cacheKeys.add((String) serializable);
                }
            });
            redisTemplate.delete(cacheKeys);
            redisTemplate.opsForZSet().remove(knownKeysName, cacheKeys);
        }
    }

    private V doGetOrLoadIfAbsent(K key, boolean loadIfAbsent) {
        String cacheKey = buildCacheKey(key);
        V value = (V) redisTemplate.opsForValue().get(cacheKey);
        if (loadIfAbsent && value == null) {
            value = multiCacheLoader.loadCache(key);
            put(key, value);
        }

        return value;
    }

    private Map<K, V> doGetOrLoadIfAbsent(Iterable<K> keys, boolean loadIfAbsent) {
        List<String> cacheKeyList = buildCacheKey(keys);
        List<List<String>> partitions = Lists.partition(cacheKeyList, MAX_BATCH_KEY_SIZE);

        List<V> valueList = Lists.newArrayListWithExpectedSize(cacheKeyList.size());

        for (List<String> partition : partitions) {
            // Get multiple keys. Values are returned in the order of the requested keys.
            List<V> values = (List<V>) redisTemplate.opsForValue().multiGet(partition);
            if (!CollectionUtils.isEmpty(values)) {
                valueList.addAll(values);
            }
        }

        List<K> keysList = Lists.newArrayList(keys);
        List<K> missedKeyList = Lists.newArrayList();

        Map<K, V> map = Maps.newHashMapWithExpectedSize(partitions.size());


        for (int i = 0; i < valueList.size(); i++) {
            V v = valueList.get(i);
            K k = keysList.get(i);
            if (v != null) {
                map.put(k, v);
            } else {
                missedKeyList.add(k);
            }
        }

        if (loadIfAbsent) {
            Map<K, V> missValueMap = multiCacheLoader.loadCache(missedKeyList);

            put(missValueMap);

            map.putAll(missValueMap);
        }

        return map;
    }


    private String buildCacheKey(K key) {
        String cacheKey = keyPrefix + ":" + keyGenerator.generate(key);
        Assert.isTrue(!knownKeysName.equals(key), "illegal argument : " + key);
        return cacheKey;
    }


    /**
     * Values are returned in the order of the requested keys.
     */
    private List<String> buildCacheKey(Iterable<K> keys) {
        List<String> cacheKeys = Lists.newArrayList();
        keys.forEach(k -> cacheKeys.add(buildCacheKey(k)));
        return cacheKeys;
    }

    @FunctionalInterface
    interface RedisKeyGenerator<K> {
        @NonNull
        String generate(@NonNull K key);
    }


    public static class DefaultRedisKeyGenerator<K> implements RedisKeyGenerator<K> {

        public static final DefaultRedisKeyGenerator INSTANCE = new DefaultRedisKeyGenerator<>();

        private DefaultRedisKeyGenerator() {
        }

        @Override
        @NonNull
        public String generate(@NonNull K key) {
            return key.toString();
        }
    }

}
