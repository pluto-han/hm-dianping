package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 从redis查看是否有list缓存
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 有就直接返回
        if(CollectionUtil.isNotEmpty(shopTypeJson)){
            // JSON -> CLASS
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson.toString(), ShopType.class);
            // sort
            Collections.sort(shopTypeList, ((o1, o2) -> o1.getSort() - o2.getSort()));

            return Result.ok(shopTypeList);
        }

        // 没有则查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 没有找到数据，返回404
        if(CollectionUtil.isEmpty(shopTypeList)){
            return Result.fail("未查到商铺类型！");
        }

        // 找到了数据，写入redis
        // Java对象 -> JSON字符串
        shopTypeJson = shopTypeList.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeJson);

        //返回数据
        return Result.ok(shopTypeList);
    }
}
