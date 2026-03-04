    package com.hmdp.service.impl;

    import cn.hutool.core.bean.BeanUtil;
    import com.hmdp.dto.Result;
    import com.hmdp.entity.VoucherOrder;
    import com.hmdp.mapper.VoucherOrderMapper;
    import com.hmdp.service.ISeckillVoucherService;
    import com.hmdp.service.IVoucherOrderService;
    import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
    import com.hmdp.utils.RedisIdWorker;
    import com.hmdp.utils.UserHolder;
    import lombok.extern.slf4j.Slf4j;
    import org.redisson.api.RLock;
    import org.redisson.api.RedissonClient;
    import org.springframework.amqp.rabbit.connection.CorrelationData;
    import org.springframework.amqp.rabbit.core.RabbitTemplate;
    import org.springframework.aop.framework.AopContext;
    import org.springframework.core.io.ClassPathResource;
    import org.springframework.data.redis.connection.stream.*;
    import org.springframework.data.redis.core.StringRedisTemplate;
    import org.springframework.data.redis.core.script.DefaultRedisScript;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;
    import org.springframework.util.concurrent.ListenableFutureCallback;

    import javax.annotation.PostConstruct;
    import javax.annotation.Resource;
    import java.time.Duration;
    import java.util.Collections;
    import java.util.List;
    import java.util.Map;
    import java.util.concurrent.ArrayBlockingQueue;
    import java.util.concurrent.BlockingQueue;
    import java.util.concurrent.ExecutorService;
    import java.util.concurrent.Executors;

    /**
     * <p>
     *  服务实现类
     * </p>
     *
     * @author 孙伟成
     * @since 2026-2-11
     */
    @Slf4j
    @Service
    public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

        @Resource
        private IVoucherOrderService voucherOrderService; // 注入代理
        @Resource
        private ISeckillVoucherService seckillVoucherService;
        @Resource
        private RedisIdWorker redisIdWorker;
        @Resource
        private StringRedisTemplate stringRedisTemplate;
        @Resource
        private RedissonClient redissonClient;
        @Resource
        private RabbitTemplate rabbitTemplate;

        // Lua 脚本
        private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
        static {
            SECKILL_SCRIPT = new DefaultRedisScript<>();
            SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
            SECKILL_SCRIPT.setResultType(Long.class);
        }


       /* // 阻塞队列：存放待处理的订单
        private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
        // 线程池：异步处理订单
        private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();*/



       /* @PostConstruct
        public void init() {
            SECKILL_ORDER_EXECUTOR.submit(() -> {
                while (true) {
                    try {
                        // 从队列中取出订单，若队列为空则阻塞
                        VoucherOrder voucherOrder = orderTasks.take();
                        // 处理订单（包含分布式锁和事务）
                        handleVoucherOrder(voucherOrder);
                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                    }
                }
            });
        }*/

        /**
         * 秒杀接口：执行 Lua 脚本，发送消息到 RabbitMQ
         */
        @Override
        public Result seckillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            // 执行 Lua 脚本（校验库存、一人一单，返回 0 表示允许购买）
            Long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString()
            );
            int r = result.intValue();
            if (r != 0) {
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }


            // 生成订单 ID

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            // 消息发送确认回调
            CorrelationData cd = new CorrelationData();
            cd.getFuture().addCallback(new ListenableFutureCallback<CorrelationData.Confirm>() {
                @Override
                public void onSuccess(CorrelationData.Confirm confirm) {
                    if (confirm.isAck()) {
                        log.debug("消息发送成功，订单ID：{}，收到ack!", orderId);
                    } else {
                        log.error("消息发送失败，订单ID：{}，收到nack! reason: {}", orderId, confirm.getReason());
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    log.error("消息发送异常，订单ID：{}，异常信息：{}", orderId, throwable.getMessage());
                }
            });
            // 发送消息到RabbitMQ
            rabbitTemplate.convertAndSend("hmdianping.direct", "direct.seckill", voucherOrder, cd);
            return Result.ok(orderId);

        }

        /**
         * 处理订单（带分布式锁）
         */
        public void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("获取分布式锁失败，用户ID：{}", userId);
                // 抛出异常触发重试（锁失败通常是临时性并发问题，可重试）
                throw new RuntimeException("获取锁失败，请重试");
            }
            try {
                // 通过代理调用，确保 @Transactional 生效
                voucherOrderService.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
        /**
         * 创建订单（包含一人一单校验和库存扣减），事务性方法
         */
        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();

            // 1. 一人一单校验
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("用户已购买过，用户ID：{}，优惠券ID：{}", userId, voucherId);
                return;
            }

            // 2. 扣减库存（乐观锁，stock > 0）
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足，优惠券ID：{}", voucherId);
                return;
            }

            // 3. 保存订单
            save(voucherOrder);
        }

    }


    /*@Slf4j
    @Service
    public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

        @Resource
        RabbitTemplate rabbitTemplate;
        @Resource
        private ISeckillVoucherService seckillVoucherService;

        @Resource
        private RedisIdWorker redisIdWorker;

        @Resource
        private StringRedisTemplate stringRedisTemplate;

        @Resource
        private RedissonClient redissonClient;

        private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
        static{
            SECKILL_SCRIPT = new DefaultRedisScript<>();
            SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
            SECKILL_SCRIPT.setResultType(Long.class);
        }

        private static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

        @PostConstruct
        private void init(){
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
        }

        private class VoucherOrderHandle implements Runnable{


            String queueName = "stream.orders";
            @Override
            public void run() {
                while(true){
                    try {
                        //XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create(queueName, ReadOffset.lastConsumed())
                        );
                        if (list == null || list.isEmpty()) {
                            continue;
                        }
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> values = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        handleVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    } catch (Exception e) {
                        log.error("处理订单异常",e);
                        handlePendingList();
                    }
                }
            }

            private void handlePendingList() {
                while(true){
                    try {
                        //XREADGROUP GROUP g1 c1 COUNT 1  STREAMS streams.order 0
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create(queueName, ReadOffset.from("0"))
                        );
                        if (list == null || list.isEmpty()) {
                            break;
                        }
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> values = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        handleVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    } catch (Exception e) {
                        log.error("处理pending-list订单异常",e);
                    }
                }
            }
        }
        *//*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
        private class VoucherOrderHandle implements Runnable{


            @Override
            public void run() {
               while(true){
                   try {
                       VoucherOrder voucherOrder = orderTasks.take();
                       handleVoucherOrder(voucherOrder);
                   } catch (InterruptedException e) {
                       log.error("处理订单异常",e);
                   }
               }
            }
        }*//*

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return ;
            }

            try {

                proxy.createVoucherOrder(voucherOrder);
            }finally {
                lock.unlock();
            }
        }

        private IVoucherOrderService proxy;


        public Result seckillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            Long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
            );

            int r = result.intValue();
            if (r != 0) {
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }

            proxy = (IVoucherOrderService) AopContext.currentProxy();

            return Result.ok(orderId);
        }

        *//* @Override
        public Result seckillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString()
            );

            int r = result.intValue();
            if (r != 0) {
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            orderTasks.add(voucherOrder);
            proxy = (IVoucherOrderService) AopContext.currentProxy();


            return Result.ok(orderId);
        }
    *//*
        *//*@Override
        public Result seckillVoucher(Long voucherId) {
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
                return Result.fail("秒杀尚未开始");
            }

            if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
                return Result.fail("秒杀已经结束！");
            }

            if (voucher.getStock() < 1) {
                return Result.fail("库存不足！");
            }

            Long userId = UserHolder.getUser().getId();
            //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                return Result.fail("不允许重复下单");
            }

            try {
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
            }finally {
                lock.unlock();
           }

        }
    *//*
        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = UserHolder.getUser().getId();


                int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
                if (count > 0) {
                    log.error("用户已经购买过一次！");
                    return;
                }

                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                        .update();

                if (!success) {
                    log.error("库存不足！");
                    return;
                }


                save(voucherOrder);

        }
    }
*/

    //直接同步处理 + RabbitMQ 重试机制
    /*
    @Service
    public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

        @Resource
        private ISeckillVoucherService seckillVoucherService;
        @Resource
        private IVoucherOrderService iVoucherOrderService;

        @Resource
        private RedisIdWorker redisIdWorker;
        @Resource
        private StringRedisTemplate stringRedisTemplate;

        @Resource
        private RedissonClient redissonClient;


        private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
        static {
            SECKILL_SCRIPT = new DefaultRedisScript<>();
            SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
            SECKILL_SCRIPT.setResultType(Long.class);
        }

        private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

        //异步处理线程池
        private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


        @Transactional
        public void handleVoucherOrder(VoucherOrder voucherOrder) {
            //1.所有信息从当前消息实体中拿
            Long voucherId = voucherOrder.getVoucherId();
            //2.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                    .eq("voucher_id", voucherId)
                    //======判断当前库存是否大于0就可以决定是否能抢池子中的券了
                    .gt("stock", 0)
                    .update();
            //3.创建订单
            if(success) save(voucherOrder);
        }

        @Resource
        RabbitTemplate rabbitTemplate;
        @Override
        public Result seckillVoucher(Long voucherId) {
            //1.执行lua脚本，判断当前用户的购买资格
            Long userId = UserHolder.getUser().getId();
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString());
            if (result != 0) {
                //2.不为0说明没有购买资格
                return Result.fail(result==1?"库存不足":"不能重复下单");
            }
            //3.走到这一步说明有购买资格，将订单信息存到消息队列
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            //存入消息队列等待异步消费
            rabbitTemplate.convertAndSend("hmdianping.direct","direct.seckill",voucherOrder);
            return Result.ok(orderId);
        }



        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder){
            // 5.一人一单逻辑
            // 5.1.用户id
            Long userId = voucherOrder.getId();

            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("用户已经购买过一次！");
                return ;
            }

            //6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1") // set stock = stock -1
                    .eq("voucher_id", voucherOrder)
                    .gt("stock",0)// where id = ? and stock > 0
                    .update();
            if (!success) {
                //扣减库存
                log.error("库存不足！");
                return ;
            }


            save(voucherOrder);

        }
    }
*/