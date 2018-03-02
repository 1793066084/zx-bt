package com.zx.bt.task;

import com.zx.bt.config.Config;
import com.zx.bt.entity.Node;
import com.zx.bt.store.RoutingTable;
import com.zx.bt.util.BTUtil;
import com.zx.bt.util.SendUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * author:ZhengXing
 * datetime:2018-02-17 11:23
 * find_node请求 任务
 */
@Component
@Slf4j
public class FindNodeTask {

    private final Config config;
    private final List<String> nodeIds;
    private final ReentrantLock lock;
    private final Condition condition;
    private final List<RoutingTable> routingTables;

    public FindNodeTask(Config config, List<RoutingTable> routingTables) {
        this.config = config;
        this.nodeIds = config.getMain().getNodeIds();
        this.routingTables = routingTables;
        this.queue = new LinkedBlockingDeque<>(10240);
        this.lock = new ReentrantLock();
        this.condition = this.lock.newCondition();
    }

    /**
     * 发送队列
     */
    private BlockingDeque<FindNode> queue;

    /**
     * 入队首
     */
    public void put(InetSocketAddress address) {
        //如果失败
        if (!queue.offer(new FindNode(address))) {
            //从末尾移除一个
            queue.pollLast();
            //再次增加..当然.是不保证成功的.但是总会有一个最新的插进去
            queue.offer(new FindNode(address));
        }
    }

    /**
     * 循环执行该任务
     */
    public void start() {
        //暂停时长
        int pauseTime = config.getPerformance().getFindNodeTaskIntervalMillisecond();
        new Thread(()->{
            int max = Integer.MAX_VALUE - 10000;
            int i = 0;
            int size = nodeIds.size();
            while (true) {
                try {
                    run(i++ % size);
                    pause(pauseTime,TimeUnit.MILLISECONDS);
                    if(i > max)
						i = 0;
                } catch (Exception e) {
                    log.error("[FindNodeTask]异常.error:{}",e.getMessage(),e);
                }
            }
        }).start();
    }

    /**
     * 每x分钟,往find_node队列中增加要发送的目标地址
     */
    @Scheduled(cron = "0 0/10 * * * ? ")
    public void autoPutToFindNodeQueue() {
        for (int j = 0; j < routingTables.size(); j++) {
            RoutingTable routingTable = routingTables.get(j);
            try {
                List<Node> nodeList = routingTable.getForTop8(BTUtil.generateNodeId());
                if(CollectionUtils.isNotEmpty(nodeList))
                    nodeList.forEach(item ->put(item.toAddress()));
            } catch (Exception e) {
                log.info("[autoPutToFindNodeQueue]异常.e:{}",e.getMessage(),e);
            }
        }
    }

    /**
     * 更新线程
     * 每x分钟,更新一次要find_Node的目标节点
     */
    @Scheduled(cron = "0 0/3 * * * ? ")
    public void updateTargetNodeId() {
        config.getMain().setTargetNodeId(BTUtil.generateNodeIdString());
        log.info("已更新TargetNodeId");
    }


    /**
     * 任务
     */
    @SneakyThrows
    public void run(int index) {
        SendUtil.findNode(queue.take().getAddress(),nodeIds.get(index),BTUtil.generateNodeIdString(),index);
    }

    /**
     * 暂停指定时间
     */
    public void pause(long time, TimeUnit timeUnit) {
        try {
            lock.lock();
            condition.await(time, timeUnit);
        } catch (Exception e){
            //..不可能发生
        }finally {
            lock.unlock();
        }
    }

    /**
     * 长度
     */
    public int size() {
        return queue.size();
    }

    /**
     * 待发送任务实体
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Accessors(chain = true)
    public static class FindNode{
        /**
         * 目标地址
         */
        private InetSocketAddress address;

        /**
         * 索引
         */
//        private int index;
    }


}
