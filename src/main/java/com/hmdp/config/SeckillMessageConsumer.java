package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Component
public class SeckillMessageConsumer {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "direct.seckill.queue", durable = "true"),
            key = "direct.seckill",
            exchange = @Exchange(name = "hmdianping.direct", type = ExchangeTypes.DIRECT, durable = "true")
    ))
    public void receiveMessage(Message message, Channel channel, VoucherOrder voucherOrder) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            voucherOrderService.handleVoucherOrder(voucherOrder);
            channel.basicAck(deliveryTag, false);
            log.debug("订单处理成功，手动ACK，订单ID：{}", voucherOrder.getId());
        } catch (Exception e) {
            log.error("订单处理失败，将触发重试，订单ID：{}", voucherOrder.getId(), e);
            throw e; // 只抛出异常，不手动Nack，让Spring重试机制处理
        }
    }
}