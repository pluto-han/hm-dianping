package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.logging.log4j.message.ReusableMessage;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;


    /**
     * 抢购秒杀券
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result secKillVoucher(Long voucherId) {
        // 查询秒杀券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        // 判断秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if(beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        }

        // 判断秒杀是否结束
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if(endTime.isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }

        // 判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if(stock < 1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            /*
                Transactional注解使用动态代理来生效，代理对象生效。但是直接调用方法，调用的是this里的方法。事务会失效
             */
            // return createVoucherOrder(voucherId);
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 查询订单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        // 判断是否有多个订单
        if(count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if(!success){
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 全局唯一订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);

        // 代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }
}
