package com.github.shenjianeng.easycache.demo;

import com.github.shenjianeng.easycache.core.Cache;
import com.github.shenjianeng.easycache.core.RedisCache;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.Serializable;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author shenjianeng
 * @date 2020/4/4
 */
@Service
public class UserService {

    public Cache<Integer, User> userCache;
    @Autowired
    private RedisTemplate<String, Serializable> redisTemplate;

    @PostConstruct
    public void init() {
        userCache =
                new RedisCache<>("user", redisTemplate, Duration.ofMinutes(5),
                        keys -> keys.stream().map(k -> {
                            User user = new User();
                            user.setId(k);
                            user.setName("user:" + k);
                            return user;
                        }).collect(Collectors.toMap(User::getId, Function.identity())));
    }

    @Data
    public static class User implements Serializable {
        private static final long serialVersionUID = 4328907645111673957L;
        private int id;
        private String name;
    }

}
