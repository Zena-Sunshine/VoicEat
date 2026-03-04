package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 孙伟成
 * @since 2026-2-5
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;

        String shopType = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopType)) {
            return Result.ok(JSONUtil.toList(shopType,ShopType.class));
        }

        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        if (CollectionUtil.isEmpty(shopTypeList)) {
            return Result.fail("商铺类型列表为空!");
        }

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));

        return Result.ok(shopTypeList);

    }
}
