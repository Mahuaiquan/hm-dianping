package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class VoucherOrderOversellTest {

    private static final int INITIAL_STOCK = 10;
    private static final int REQUEST_COUNT = 100;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Test
    void shouldNotOversellUnderConcurrentSeckillRequests() throws InterruptedException {
        Long voucherId = createTestVoucher();
        ExecutorService executorService = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();

        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                final long userId = 100_000L + i;
                executorService.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        UserDTO user = new UserDTO();
                        user.setId(userId);
                        UserHolder.saveUser(user);

                        Result result = voucherOrderService.seckilVoucher(voucherId);
                        if (Boolean.TRUE.equals(result.getSuccess())) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        UserHolder.removeUser();
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "concurrent workers did not start in time");
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent seckill requests did not finish in time");

            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            int remainingStock = voucher.getStock();
            int orderCount = voucherOrderService.count(
                    new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId)
            );

            assertEquals(INITIAL_STOCK, successCount.get(), "successful requests should equal initial stock");
            assertEquals(INITIAL_STOCK, orderCount, "created orders should equal initial stock");
            assertEquals(0, remainingStock, "stock should be sold out exactly");
            assertTrue(remainingStock >= 0, "stock must never become negative");
            assertEquals(INITIAL_STOCK, orderCount + remainingStock, "orders plus remaining stock should stay constant");
        } finally {
            executorService.shutdownNow();
            cleanTestVoucher(voucherId);
        }
    }

    private Long createTestVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("oversell-test-voucher");
        voucher.setSubTitle("oversell-test");
        voucher.setRules("oversell-test");
        voucher.setPayValue(1L);
        voucher.setActualValue(100L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucherService.save(voucher);

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(INITIAL_STOCK);
        seckillVoucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        seckillVoucher.setEndTime(LocalDateTime.now().plusMinutes(10));
        seckillVoucherService.save(seckillVoucher);

        return voucher.getId();
    }

    private void cleanTestVoucher(Long voucherId) {
        if (voucherId == null) {
            return;
        }
        voucherOrderService.remove(new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId));
        seckillVoucherService.removeById(voucherId);
        voucherService.removeById(voucherId);
    }
}
