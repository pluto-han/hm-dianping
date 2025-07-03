package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time,  TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        // 根据id查询redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 判断是否存在
        if(StrUtil.isNotBlank(json)){
            // 若在redis中查到，则直接返回数据
            return JSONUtil.toBean(json, type);
        }

        // 判断是否是空值,防止用空值访问数据库
        if(json != null){  // "".equals(shopJson)
            return null;
        }

        // 若redis中未查到，则根据id访问数据库
        R r = dbFallBack.apply(id);
        if(r == null) {
            // 数据库未查到，将空值写入redis，防止缓存穿透（new）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        // 数据库查到，则将数据写入redis。添加ttl，实现超时剔除，为缓存更新策略兜底
        //stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, timeUnit);
        this.set(key, r, time, timeUnit);

        // 返回数据
        return r;
    }


    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 利用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        // 1. 根据id查询redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if(StrUtil.isBlank(json)){
            // 3. 不存在，直接返回null
            return null;
        }

        // 4. 命中，则需要先将json反序列化为java对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 5 判断是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，则返回数据
            return r;
        }

        // 6. 过期，则开始缓存重建
        // 6.1 尝试获取互斥锁
        String lockKey = lockPrefix + id;
        boolean islock = tryLock(lockKey);

        // 6.2 判断互斥锁是否获取成功
        if(islock){
            // 获取成功之后还要检查一次缓存是否过期，如果未过期则无需缓存重建
            // 防止在缓存重建之后的瞬间，一个线程又获取了互斥锁


            // 6.3 成功，则开启新进程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查数据库
                    R newR = dbFallBack.apply(id);
                    // 写入缓存
                    this.setWithLogicalExpire(key, newR, time,  timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6.4 返回过期的商铺信息 （不管获取成功与否，都要返回旧数据）
        return r;
    }


    public <R, ID> R queryWithMutex(
            String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time,  TimeUnit timeUnit) {
        // 根据id查询redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 判断是否存在
        if(StrUtil.isNotBlank(json)){
            // 若在redis中查到，则直接返回数据
            return JSONUtil.toBean(json, type);
        }

        // 判断是否是空值,防止用空值访问数据库
        if(json != null){  // "".equals(shopJson)
            return null;
        }

        // 若redis中未查到，则根据id访问数据库
        // 1. 实现缓存重建，获取互斥锁
        String lockKey = lockPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);

            // 2. 判断互斥锁是否存在
            if(!isLock){
                // 3. 失败则休眠一段时间，并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockPrefix, id, type, dbFallBack, time, timeUnit);
            }

            // 4. 成功，查询数据库
            r = dbFallBack.apply(id);
            Thread.sleep(200);
            if(r == null) {
                // 数据库未查到，将空值写入redis，防止缓存穿透（new）
                //stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            // 数据库查到，则将数据写入redis。添加ttl，实现超时剔除，为缓存更新策略兜底
            //stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            this.set(key, r, time,  timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 5. 释放互斥锁
            unlock(lockKey);
        }

        // 返回数据
        return r;
    }

    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
