package com.github.shenjianeng.easycache.demo;

import com.github.shenjianeng.easycache.EasyCacheApplication;
import com.github.shenjianeng.easycache.core.Cache;
import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

/**
 * @author shenjianeng
 * @date 2020/4/4
 */
public class UserServiceTest {

    @Test
    public void test() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EasyCacheApplication.class);
        context.start();

        UserService userService = context.getBean(UserService.class);
        Cache<Integer, UserService.User> userCache = userService.userCache;
        userCache.evictAll();

        UserService.User user1 = userCache.getIfPresent(1);
        Assert.assertNull(user1);


        user1 = userCache.getOrLoadIfAbsent(1);
        Assert.assertNotNull(user1);
        Assert.assertEquals(1, user1.getId());
        System.out.println(user1);

        userCache.evict(1);
        user1 = userCache.getOrLoadIfAbsent(1);
        Assert.assertNotNull(user1);
        Assert.assertEquals(1, user1.getId());


        user1 = userCache.getOrLoadIfAbsent(1);
        Assert.assertNotNull(user1);
        Assert.assertEquals(1, user1.getId());
        System.out.println(user1);

        UserService.User user2 = userCache.getOrLoadIfAbsent(2);
        Assert.assertNotNull(user2);
        Assert.assertEquals(2, user2.getId());
        System.out.println(user2);

        Map<Integer, UserService.User> map = userCache.getIfPresent(Lists.newArrayList(1, 2));
        Assert.assertNotNull(map);
        Assert.assertEquals(user1, map.get(1));
        Assert.assertEquals(user2, map.get(2));

        userCache.evict(Lists.newArrayList(1, 2));
        map = userCache.getIfPresent(Lists.newArrayList(1, 2));

        Assert.assertTrue(map.isEmpty());

        map = userCache.getOrLoadIfAbsent(Lists.newArrayList(1, 2));
        Assert.assertNotNull(map);
        Assert.assertEquals(user1, map.get(1));
        Assert.assertEquals(user2, map.get(2));

        userCache.evictAll();
        map = userCache.getIfPresent(Lists.newArrayList(1, 2));
        Assert.assertTrue(map.isEmpty());

        context.close();

    }
}