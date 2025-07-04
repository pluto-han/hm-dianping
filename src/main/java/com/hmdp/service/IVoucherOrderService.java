package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 抢购秒杀券
     * @param voucherId
     * @return
     */
    Result secKillVoucher(Long voucherId);

    /**
     * 方法：创建秒杀券订单
     * 获取代理对象（事务）
     * @param voucherId
     * @return
     */
    Result createVoucherOrder(Long voucherId);
}
