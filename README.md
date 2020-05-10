# Spring Cache 缺陷，我好像有解决方案了

## Spring Cache 缺陷

Spring Cache 是一个非常优秀的缓存组件。

但是在使用 Spring Cache 的过程当中，小黑同学也遇到了一些痛点。

比如，现在有一个需求：通过多个 userId 来批量获取用户信息。

### 方案 1

此时，我们的代码可能是这样：

```java
List<User> users = ids.stream().map(id -> {
    return getUserById(id);
})
.collect(Collectors.toList());

@Cacheable(key = "#p0", unless = "#result == null")
public User getUserById(Long id) {
	// ···
}
```

这种写法的缺点在于：

在 for 循环中操作 redis。如果数据命中缓存还好，一旦缓存没有命中，则会访问数据库。

### 方案 2

也有的同学可能会这样做：

```java
@Cacheable(key = "#ids.hash")
public Collection<User> getUsersByIds(Collection<Long> ids) {
	// ···
}
```

这种做法的问题是：

缓存是基于 id 列表的 hashcode ，只有在 id 列表的 hashcode 值相等的情况下，缓存才会命中。而且，一旦列表中的其中一个数据被修改，整个列表缓存都要被清除。

> 例如：
>
> 第一次请求 id 列表是 `1,2,3,`
>
> 第二次请求的 id 列表为 `1,2,4`
>
> 在这种情况下，前后两次的缓存不能共享。
>
> 如果 id 为 1 的数据发生了改变，那么，这两次请求的缓存都要被清空

## 看看 Spring 官方是怎么说的

Spring Issue：

> https://github.com/spring-projects/spring-framework/issues/24139
>
> https://github.com/spring-projects/spring-framework/issues/23221

![image.png](https://upload-images.jianshu.io/upload_images/14270210-81e98153d60102a9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

简单翻译一下，具体内容读者可以自行查阅相关 issue。

> 译文：
>
> 谢谢你的报告。缓存抽象没有这种状态的概念，如果你返回一个集合，那就是你要求在缓存中存储的东西。也没有什么强迫您为给定的缓存保留相同的项类型，所以这种假设并不适合这样的高级抽象。

我的理解是，对于 Spring Cache 这种高级抽象框架来说，Cache 是基于方法的，如果方法返回 Collection，那整个 Collection 就是需要被缓存的内容。

## 我的解决方案

纠结了好久，小黑同学还是决定自己来造个轮子。

那我想要达到什么样的效果呢？

我希望对于这种根据多个 key 批量获取缓存的操作，可以先根据单个 key 从缓存中查找，如果缓存中不存在，就去加载数据，同时再将数据放到缓存中。

![talk is cheap,show me the code](https://upload-images.jianshu.io/upload_images/14270210-555c270289ee0a8a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

废话不多说，直接上源码：

https://github.com/shenjianeng/easy-cache

简单介绍一下整体的思路：

- 核心接口

  - com.github.shenjianeng.easycache.core.Cache

  - com.github.shenjianeng.easycache.core.MultiCacheLoader

### Cache 接口

Cache 接口定义了一些通用的缓存操作。和大部分 Cache 框架不同是，这里支持根据 key 批量获取缓存。

```java
/**
 * 根据 keys 缓存中获取,缓存中不存在,则返回null
 */
@NonNull
Map<K, V> getIfPresent(@NonNull Iterable<K> keys);


/**
 * 根据 keys 从缓存中获取,如果缓存中不存在,调用 {@link MultiCacheLoader#loadCache(java.util.Collection)} 加载数据,并添加到缓存中
 */
@NonNull
Map<K, V> getOrLoadIfAbsent(@NonNull Iterable<K> keys);
```

### MultiCacheLoader 接口

```java
@FunctionalInterface
public interface MultiCacheLoader<K, V> {

    @NonNull
    Map<K, V> loadCache(@NonNull Collection<K> keys);

    default V loadCache(K key) {
        Map<K, V> map = loadCache(Collections.singleton(key));
        if (CollectionUtils.isEmpty(map)) {
            return null;
        }
        return map.get(key);
    }
}
```

MultiCacheLoader 是一个函数式接口。在调用 `Cache#getOrLoadIfAbsent` 方法时，如果缓存不存在，就会通过 MultiCacheLoader 来加载数据，然后加数据放到缓存中。

### RedisCache

RedisCache 是现在 Cache 接口的唯一实现。正如其类名一样，这是基于 redis 的缓存实现。

先说一下大致的实现思路：

1. 使用 redis 的 mget 命令，批量获取缓存。为了保证效率，每次最多批量获取 20 个。
2. 如果有数据不在缓存中，则判断是否需要自动加载数据，如果需要则通过 MultiCacheLoader 加载数据
3. 将数据存放到缓存中。同时通过维护一个 zset 来保存已知的 cache key，用于清除缓存使用。

废话不多说，直接上源码。

```java
private Map<K, V> doGetOrLoadIfAbsent(Iterable<K> keys, boolean loadIfAbsent) {
    List<String> cacheKeyList = buildCacheKey(keys);
    List<List<String>> partitions = Lists.partition(cacheKeyList, MAX_BATCH_KEY_SIZE);

    List<V> valueList = Lists.newArrayListWithExpectedSize(cacheKeyList.size());

    for (List<String> partition : partitions) {
        // Get multiple keys. Values are returned in the order of the requested keys.
        List<V> values = (List<V>) redisTemplate.opsForValue().multiGet(partition);
        valueList.addAll(values);
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
```

缓存清除方法实现：

```java
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
```

## 再多说几句

更多源码细节，如果读者感兴趣，可以自行阅读源码：[easy-cache](https://github.com/shenjianeng/easy-cache)

欢迎大家 fork 体验，或者评论区留言探讨，写的不好，请多多指教~~

未来计划：

- 支持缓存 null 值
- 支持 annotation 的声明式缓存

