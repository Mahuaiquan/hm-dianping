package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Configuration
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value,Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLongExpire(String key, Object value,Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

     //分装解决缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefx , ID id, Class<R> type, Function<ID,R> function, Long time, TimeUnit timeUnit){
    String key = keyPrefx + id;
    String json = stringRedisTemplate.opsForValue().get(key);

    if(StrUtil.isNotEmpty(json)){
        return JSONUtil.toBean(json, type);
    }

    if(json != null){
        return null;
    }

    R r = function.apply(id);
    if(r == null){
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        return null;
    }
    this.set(key,r,time,timeUnit);

    return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                    Function<ID, R> function, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;

        while (true) {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotEmpty(json)) {
                return JSONUtil.toBean(json, type);
            }
            // An empty string is the cache-penetration placeholder.
            if (json != null) {
                return null;
            }

            String lockKey = LOCK_SHOP_KEY + id;
            if (!trylock(lockKey)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for cache rebuild", e);
                }
                continue;
            }

            try {
                // A previous lock holder might have populated the cache before this lock was acquired.
                json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotEmpty(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }

                R r = function.apply(id);
                if (r == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                this.set(key, r, time, timeUnit);
                return r;
            } finally {
                unlock(lockKey);
            }
        }
    }

    private void  unlock(String key){stringRedisTemplate.delete(key);}

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

}
