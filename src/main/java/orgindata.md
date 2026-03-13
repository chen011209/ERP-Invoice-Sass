为什么要自己做幂等？
诺诺的幂等是 “弱幂等”，不是 “强幂等”，它的幂等是有时效性的，时间过后再用相同orderNo开会重复开票
不自己做幂等 迟早重复开票

开票失败可以用原单id重新开票。开票成功，红冲后，想修改明细重新开票。需要用新单id再开票
场景 1：开票失败（税号错了）
单据 a → 状态：开票失败
改正税号 → 再次用单据 a 调用诺诺
成功 → 状态改成：已开票
场景 2：开票成功（后来发现错了）
单据 a → 状态：已开票
必须红冲 a → a 状态变成：已红冲
改正内容 → 新开单据 b 开票



数据库设计
1、消息表（为了性能可以改成消息表和报文表）
2、租户配置表

调用诺诺开票，第一次只返回id，提交开票成功，后续开票结果是回调的


CREATE TABLE `tenant_config` (
`id` BIGINT PRIMARY KEY AUTO_INCREMENT,
`tenant_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '租户业务ID',
-- 1. 鉴权接入凭证 (你发给法务/OA/南北的)
`client_id` VARCHAR(64) NOT NULL,
`client_secret` VARCHAR(128) NOT NULL,
-- 2. 供应商核心配置 (诺诺的)
`app_id` VARCHAR(128) NOT NULL,
`app_secret` VARCHAR(128) NOT NULL,
-- 3、 erp的secret（中间件调用erp）
erp_id
erp_secret
-- 4. 租户管控
`status` TINYINT DEFAULT 1 COMMENT '1-启用, 0-禁用',
`callback_url` VARCHAR(255) COMMENT '业务方回调地址',
`updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);



@Component
public class SimpleTenantAuthInterceptor implements HandlerInterceptor {

    @Resource
    private TenantConfigMapper tenantConfigMapper;
    
    // 注入 StringRedisTemplate 做防重放（1行搞定）
    @Resource
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = request.getHeader("X-Client-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce"); // 新增
        String sign = request.getHeader("X-Sign");

        // 1. 校验 Header 必须完整
        if (clientId == null || timestamp == null || nonce == null || sign == null) {
            throw new RuntimeException("拒绝访问：缺少鉴权参数");
        }

        // 2. 时间窗口防过期（5分钟）
        long reqTime = Long.parseLong(timestamp);
        if (Math.abs(System.currentTimeMillis() - reqTime) > 5 * 60 * 1000) {
            throw new RuntimeException("拒绝访问：请求已过期");
        }

        // 3. 防重放核心：同一个 nonce 5分钟内只能用一次
        String redisKey = "auth:nonce:" + clientId + ":" + nonce;
        Boolean absent = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(absent)) {
            throw new RuntimeException("拒绝访问：请求重复提交");
        }

        // 4. 校验租户合法性
        TenantConfig tenant = tenantConfigMapper.selectByClientId(clientId);
        if (tenant == null || tenant.getStatus() == 0) {
            throw new RuntimeException("拒绝访问：非法ClientId");
        }

        // 5. 安全验签（加入 nonce）
        String expectSign = DigestUtil.md5Hex(clientId + timestamp + nonce + tenant.getClientSecret());
        if (!expectSign.equalsIgnoreCase(sign)) {
            throw new RuntimeException("拒绝访问：签名错误");
        }

        // 6. 租户上下文
        TenantContext.setTenantId(tenant.getTenantId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}



public enum InvoiceStatus {
INIT,           // 待开票 (蓝票) INIT就是把消息放到MQ中，待消费者处理
PROCESSING,     // 开票中 (蓝票) PROCESSING就是消费者消费后，正在处理
WAIT_CALLBACK,  // 【新增】已提交税局，等待诺诺回调通知结果！
SUCCESS,        // 开票成功 (蓝票终态)
FAIL,           // 开票失败 (蓝票终态，允许在原单上修改信息并重试)

RED_INIT,       // 待红冲 (红票)
RED_PROCESSING, // 红冲中 (红票)
RED_WAIT_CALLBACK, // 【新增】等待红冲回执
RED_SUCCESS,    // 已红冲/红冲成功 (红票终态 - 即你说的单据A的最终归宿)
RED_FAIL        // 红冲失败 (允许重试红冲)
}

@Data
@TableName("invoice_record")
public class InvoiceRecord {
@TableId(type = IdType.AUTO)
private Long id;

    private String orderId;         // 唯一单据号
    private InvoiceStatus status;   // 当前状态
    
    private String nuonuoSerialNo;  // 【新增】诺诺返回的受理流水号(极重要，用于接收回调和查单)
    
    private String reqPayload;      // 请求报文
    private String resPayload;      // 成功后的发票信息(PDF链接等)
    private String errorMsg;        // 失败原因
    
    private Integer retryCount;     // 自动重试次数
    private Date createTime;
    private Date updateTime;
}


@Service
@Slf4j
public class InvoiceBlueService {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Transactional(rollbackFor = Exception.class)
    public String submitBlueInvoice(String orderId, String payload) {
        InvoiceRecord record = invoiceRecordMapper.selectByOrderId(orderId);

        if (record != null) {
            switch (record.getStatus()) {
                case SUCCESS:
                    return "单据已开票成功，严禁修改重开！请先红冲原单据，生成新单据号重新发起。";
                
                // 【核心修改】：把 WAIT_CALLBACK 纳入不可触碰的保护区
                case WAIT_CALLBACK:
                case RED_WAIT_CALLBACK:
                    return "单据已提交税局，正在等待税局异步返回结果，请耐心等待，严禁此时修改或重试！";
                    
                case PROCESSING:
                case RED_PROCESSING:
                case INIT:
                case RED_INIT:
                    return "单据正在队列排队或提交中，请勿重复操作。";
                    
                case RED_SUCCESS:
                case RED_FAIL:
                    return "单据已进入红冲生命周期，严禁再次发起正向开票。";

                case FAIL:
                    // 允许重试，状态改回 INIT，重置重试次数
                    int affected = invoiceRecordMapper.updateForUpstreamRetry(
                            orderId, payload, InvoiceStatus.FAIL.name(), InvoiceStatus.INIT.name());
                    if (affected == 1) {
                        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:BLUE", orderId);
                        return "修改单据并重新发起开票成功";
                    }
                    return "单据状态已变更，请勿频繁操作";
            }
        }

        // 首次落库
        try {
            record = new InvoiceRecord();
            record.setOrderId(orderId);
            record.setStatus(InvoiceStatus.INIT);
            record.setReqPayload(payload);
            record.setRetryCount(0);
            invoiceRecordMapper.insert(record);
            
            rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:BLUE", orderId);
            return "开票请求受理成功";
        } catch (DuplicateKeyException e) {
            return "请求正在受理中，请勿频繁点击";
        }
    }
}

@Service
@Slf4j
public class InvoiceRedService {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Transactional(rollbackFor = Exception.class)
    public String submitRedInvoice(String orderId, String redPayload) {
        InvoiceRecord record = invoiceRecordMapper.selectByOrderId(orderId);

        if (record == null) {
            return "单据不存在，无法发起红冲";
        }

        switch (record.getStatus()) {
            case SUCCESS:
                // 修复后（绝对安全的乐观锁防并发）：
                // 只有真正把 SUCCESS 状态改成 RED_INIT 的那唯一一个线程，才有资格发 MQ！
                int affected = invoiceRecordMapper.updateForUpstreamRetry(
                        orderId, redPayload, InvoiceStatus.SUCCESS.name(), InvoiceStatus.RED_INIT.name());
                        
                if (affected == 1) {
                    sendToMq(orderId, "RED");
                    return "红冲请求受理成功";
                } else {
                    // 如果受影响行数为 0，说明被另一个并发请求抢先改成了 RED_INIT
                    log.warn("并发发起红冲被拦截, orderId: {}", orderId);
                    return "单据正在发起红冲中，请勿频繁操作";
                }
            // 【核心修改】：把 WAIT_CALLBACK 纳入不可触碰的保护区
            case WAIT_CALLBACK:
            case RED_WAIT_CALLBACK:
                return "单据已提交税局，正在等待税局异步返回结果，请耐心等待，严禁此时修改或重试！";           
                
            case RED_FAIL:
                // 之前红冲失败了，南北系统修改红冲报文后再次发起红冲
                // 使用乐观锁防止并发，并将状态改回 RED_INIT，重试次数归零
                int affected = invoiceRecordMapper.updateForUpstreamRetry(
                        orderId, redPayload, InvoiceStatus.RED_FAIL.name(), InvoiceStatus.RED_INIT.name());
                        
                if (affected == 1) {
                    sendToMq(orderId, "RED");
                    return "重新发起红冲受理成功";
                } else {
                    return "该单据正在被自动重试或手工重试处理中，请勿频繁操作";
                }
                
            case RED_INIT:
                return "单据已在队列排队中，请勿重复提交";
            case RED_PROCESSING:
                return "该单据正在红冲处理中，请勿重复提交";
                
            case RED_SUCCESS:
                return "该单据已红冲成功(已作废)，请勿重复发起红冲";
                
            default:
                // INIT, PROCESSING, FAIL 的状态不允许红冲
                return "当前单据状态为[" + record.getStatus() + "]，不满足红冲条件（仅 SUCCESS 或 RED_FAIL 可红冲）";
        }
    }

    private void sendToMq(String orderId, String tag) {
        rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, orderId);
    }
}



@Configuration
public class RedissonConfig {

    /**
     * 初始化诺诺开票接口的全局分布式限流器
     */
    @Bean
    public RRateLimiter nuonuoRateLimiter(RedissonClient redissonClient) {
        // 1. 获取限流器实例
        RRateLimiter rateLimiter = redissonClient.getRateLimiter("nuonuo:invoice:ratelimiter");
        
        // 2. 初始化限流规则
        // RateType.OVERALL: 全局限流（所有分布式应用节点共享这个 QPS）
        // rate: 50 (产生令牌的数量)
        // rateInterval: 1 (时间间隔)
        // RateIntervalUnit.SECONDS: 秒
        // 综合含义：全局每 1 秒钟产生 50 个令牌，即最高 50 QPS
        rateLimiter.trySetRate(RateType.OVERALL, 50, 1, RateIntervalUnit.SECONDS);
        
        return rateLimiter;
    }
}


//消费者
@Slf4j
@Component
@RocketMQMessageListener(topic = "NuonuoInvoiceTopic", consumerGroup = "invoice-consumer-group", selectorExpression = "BLUE || RED")
public class InvoiceConsumer implements RocketMQListener<MessageExt> {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private RRateLimiter nuonuoRateLimiter;
    @Resource
    private NuonuoApiClient nuonuoApiClient;

    @Override
    public void onMessage(MessageExt messageExt) {
        String tag = messageExt.getTags();
        String orderId = new String(messageExt.getBody());
        boolean isBlue = "BLUE".equals(tag);

        // 1. 消费端防重：INIT -> PROCESSING
        String oldStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();
        String procStatus = isBlue ? InvoiceStatus.PROCESSING.name() : InvoiceStatus.RED_PROCESSING.name();
        
        //数据已经被处理了
        if (invoiceRecordMapper.updateStatusToProcessing(orderId, oldStatus, procStatus) == 0) {
            log.info("发票已被处理，无需重复处理，订单ID：{}", orderId);
            //返回后会自动 ACK
            return;
        }

        InvoiceRecord record = invoiceRecordMapper.selectByOrderId(orderId);

        String newStatus;
        String serialNo = null;
        String errorMsg = null;

        try {
            // 2. 限流保护诺诺
            nuonuoRateLimiter.acquire(1);

            // 3. 异步提交接口调用
            NuonuoResponse response = nuonuoApiClient.submitInvoice(record.getReqPayload(), tag);
            
            if (response.isSuccess()) {
                // 【核心改变】：提交成功，进入 WAIT_CALLBACK
                newStatus = isBlue ? InvoiceStatus.WAIT_CALLBACK.name() : InvoiceStatus.RED_WAIT_CALLBACK.name();
                serialNo = response.getNuonuoSerialNo();
            } else {
                // 提交被秒拒（如参数校验失败），直接进入 FAIL，允许重试
                newStatus = isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name();
                errorMsg = response.getErrMsg();
            }
        } catch (Exception e) {
            log.error("诺诺提交网络异常", e);
            newStatus = isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name();
            errorMsg = e.getMessage();
        }

        // 4. 落库提交结果（保存受理号）
        invoiceRecordMapper.updateSubmissionResult(orderId, procStatus, newStatus, serialNo, errorMsg);
    }
}


//回调请求
@RestController
@RequestMapping("/api/invoice/callback")
@Slf4j
@Component
public class NuonuoAsyncProcessor {

    @Resource
    private InvoiceRecordMapper invoiceRecordMapper;
    @Resource
    private NuonuoApiClient nuonuoApiClient;

    // ==========================================
    // 1. 接收诺诺异步回调
    // ==========================================
    @PostMapping("/notify")
    public String receiveNuonuoCallback(HttpServletRequest request,@RequestBody NuonuoCallbackMsg msg) {
        log.info("收到诺诺异步回调: {}", msg);
        // 假设诺诺传过来的签名放在 Header 里
        String nuonuoSign = request.getHeader("X-Nuonuo-Sign");
        if (nuonuoSign == null) {
            return "FAIL";
        }

        try {
            // 1. 粗略解析原 JSON，拿到诺诺流水号
            JSONObject json = JSON.parseObject(rawBody);
            String serialNo = json.getString("nuonuoSerialNo");

            // 2. 根据流水号查出是哪个单子，进而知道是哪个租户 (tenantId)
            InvoiceRecord record = invoiceRecordMapper.selectByNuonuoSerialNo(serialNo);
            if (record == null) {
                return "SUCCESS"; // 查不到直接返回成功，阻止诺诺重发
            }

            // 3. 查出该租户对应的诺诺 app_secret
            TenantConfig tenant = tenantConfigMapper.selectByTenantId(record.getTenantId());
            if (tenant == null) {
                return "FAIL";
            }

            // 4. 进行第三方验签比对 (这里假设诺诺的规则是: MD5(报文体 + appSecret))
            // 具体算法一定要看诺诺的官方API文档！
            String expectedSign = DigestUtil.md5Hex(rawBody + tenant.getAppSecret());
            
            if (!expectedSign.equalsIgnoreCase(nuonuoSign)) {
                log.error("诺诺回调验签失败！疑似被伪造！流水号: {}", serialNo);
                return "FAIL"; 
            }
            
            processFinalResult(record, msg.isSuccess(), msg.getInvoiceData(), msg.getRejectReason());
            return "SUCCESS"; // 必须返回SUCCESS告诉诺诺不要再重发了

        } catch (Exception e) {
            log.error("处理诺诺回调异常", e);
            return "FAIL";
        }

    }

    // ==========================================
    // 2. 定时任务：主动查单补偿 (防回调丢失)
    // ==========================================
    @Scheduled(cron = "0 0/5 * * * ?") // 每5分钟查一次
    public void queryStuckNuonuoResult() {
        // 扫出停留在 WAIT_CALLBACK 超过 10 分钟的单据
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        List<InvoiceRecord> stuckRecords = invoiceRecordMapper.selectStuckWaitCallbackRecords(threshold, 100);

        for (InvoiceRecord record : stuckRecords) {
            try {
                // 调诺诺主动查询API
                NuonuoQueryResponse queryRes = nuonuoApiClient.queryInvoiceResult(record.getNuonuoSerialNo());
                
                if (queryRes.isProcessing()) {
                    continue; // 税局还在排队，跳过
                }
                processFinalResult(record, queryRes.isSuccess(), queryRes.getInvoiceData(), queryRes.getRejectReason());
            } catch (Exception e) {
                log.error("主动查单异常, serialNo:{}", record.getNuonuoSerialNo(), e);
            }
        }
    }

    // ==========================================
    // 3. 抽取公共的状态决断逻辑（幂等安全）处理最终结果，并回调通知 ERP
    // ==========================================
    private void processFinalResult(InvoiceRecord record, boolean isSuccess, String resPayload, String rejectReason) {
        String oldStatus = record.getStatus().name();
    
        // 幂等拦截：如果已经是终态，说明之前处理过了，直接忽略
        if (!oldStatus.equals(InvoiceStatus.WAIT_CALLBACK.name()) && !oldStatus.equals(InvoiceStatus.RED_WAIT_CALLBACK.name())) {
            return;
        }
    
        boolean isBlue = oldStatus.equals(InvoiceStatus.WAIT_CALLBACK.name());
        String newStatus = isSuccess ? 
                           (isBlue ? InvoiceStatus.SUCCESS.name() : InvoiceStatus.RED_SUCCESS.name()) : 
                           (isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name());
    
        // 1. 利用乐观锁安全更新本系统的终态！
        int affected = invoiceRecordMapper.updateCallbackResult(
                record.getNuonuoSerialNo(), oldStatus, newStatus, resPayload, rejectReason);
        
        // 2. 只有抢到更新权的线程，才负责通知 ERP
        if (affected == 1) {
            log.info("单据生命周期结束! orderId:{}, serialNo:{}, 最终状态:{}", record.getOrderId(), record.getNuonuoSerialNo(), newStatus);
            
            // 3. 构建发给 ERP 的结果报文
            ErpNotifyMsg notifyMsg = new ErpNotifyMsg();
            notifyMsg.setOrderId(record.getOrderId());
            notifyMsg.setNuonuoSerialNo(record.getNuonuoSerialNo());
            notifyMsg.setStatus(newStatus);
            notifyMsg.setSuccess(isSuccess);
            notifyMsg.setInvoiceData(resPayload);
            notifyMsg.setRejectReason(rejectReason);
            notifyMsg.setInvoiceType(isBlue ? "BLUE" : "RED");
    
            // 4. 发送 MQ 给消费者调用南北系统 (ERP)
            //(或者 南北系统监听这个 Topic，根据 orderId 更新他们自己订单表的开票状态)
            rocketMQTemplate.convertAndSend("WebhookPushTopic", notifyMsg);
            log.info("已通过 MQ 下发开票结果给 ERP 系统. orderId:{}", record.getOrderId());
        }
    }
}


//定时扫描重试失败任务
@Scheduled(cron = "0 0/1 * * * ?") // 每1分钟扫描一次
public void autoRetryFailInvoices() {

    int maxRetry = 3;
    // 使用新写的阶梯式 SQL 捞取数据
    List<InvoiceRecord> failRecords = invoiceRecordMapper.selectSmartAutoRetryRecords(100);

    for (InvoiceRecord record : failRecords) {
        boolean isBlue = record.getStatus() == InvoiceStatus.FAIL;
        String oldStatus = isBlue ? InvoiceStatus.FAIL.name() : InvoiceStatus.RED_FAIL.name();
        String newStatus = isBlue ? InvoiceStatus.INIT.name() : InvoiceStatus.RED_INIT.name();
        
        // 乐观锁：状态改 INIT，retry_count + 1
        int affected = invoiceRecordMapper.updateForAutoRetry(record.getId(), oldStatus, newStatus, maxRetry);
        
        if (affected == 1) {
            String tag = isBlue ? "BLUE" : "RED";
            rocketMQTemplate.convertAndSend("NuonuoInvoiceTopic:" + tag, record.getOrderId());
            log.info("触发阶梯自动重试. OrderId:{}, 当前重试次数:{}/{}", record.getOrderId(), record.getRetryCount() + 1, maxRetry);
        }
    }
}

@Component
@RocketMQMessageListener(topic = "WebhookPushTopic", consumerGroup = "webhook-push-group")
public class WebhookDispatcher implements RocketMQListener<ErpNotifyMsg> {

    @Resource
    private TenantConfigMapper tenantConfigMapper;

    @Override
    public void onMessage(ErpNotifyMsg msg) {
        // 1. 查询该单子所属租户的回调地址
        TenantConfig tenant = tenantConfigMapper.selectByTenantId(msg.getTenantId());
        if (StrUtil.isBlank(tenant.getCallbackUrl())) {
            return; // 租户没配置回调地址，不需要推
        }

        try {
            // 2. 为了安全，中间件推给 ERP 时，也要做签名！(让 ERP 验证是你推的)
            String payload = JSON.toJSONString(msg);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = hmacSha256(payload + timestamp, tenant.getErpSecret());

            // 3. 执行 HTTP POST 推送
            HttpResponse response = HttpRequest.post(tenant.getCallbackUrl())
                    .header("X-Gateway-Sign", signature)
                    .header("X-Timestamp", timestamp)
                    .body(payload)
                    .timeout(5000) // 超时设置很重要，防止 ERP 卡死拖垮消费者
                    .execute();

            if (response.getStatus() != 200 || !"SUCCESS".equals(response.body())) {
                // 如果 ERP 返回的不是 SUCCESS，抛出异常！
                // RocketMQ 会自动进行阶梯重试（10s, 30s, 1m, 2m...最多16次）
                throw new RuntimeException("ERP返回失败，触发MQ延迟重试");
            }
        } catch (Exception e) {
            log.error("推送ERP回调失败, tenantId:{}, URL:{}", tenant.getTenantId(), tenant.getCallbackUrl(), e);
            throw new RuntimeException("HTTP调用异常，触发MQ重试"); 
        }
    }
}



@Mapper
public interface InvoiceRecordMapper extends BaseMapper<InvoiceRecord> {

    @Select("SELECT * FROM invoice_record WHERE order_id = #{orderId}")
    InvoiceRecord selectByOrderId(@Param("orderId") String orderId);

    @Select("SELECT * FROM invoice_record WHERE nuonuo_serial_no = #{serialNo}")
    InvoiceRecord selectByNuonuoSerialNo(@Param("serialNo") String serialNo);

    // ================== 消费者端/回调端 防御 ==================
    @Update("UPDATE invoice_record SET status = #{newStatus}, update_time = NOW() " +
            "WHERE order_id = #{orderId} AND status = #{oldStatus}")
    int updateStatusToProcessing(@Param("orderId") String orderId, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus);

    @Update("UPDATE invoice_record SET status = #{newStatus}, nuonuo_serial_no = #{serialNo}, error_msg = #{errorMsg}, update_time = NOW() " +
            "WHERE order_id = #{orderId} AND status = #{oldStatus}")
    int updateSubmissionResult(@Param("orderId") String orderId, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus, @Param("serialNo") String serialNo, @Param("errorMsg") String errorMsg);

    @Update("UPDATE invoice_record SET status = #{newStatus}, res_payload = #{resPayload}, error_msg = #{errorMsg}, update_time = NOW() " +
            "WHERE nuonuo_serial_no = #{serialNo} AND status = #{oldStatus}")
    int updateCallbackResult(@Param("serialNo") String serialNo, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus, @Param("resPayload") String resPayload, @Param("errorMsg") String errorMsg);

    // ================== 重试与补偿 防御 ==================
    @Update("UPDATE invoice_record SET status = #{newStatus}, req_payload = #{payload}, retry_count = 0, update_time = NOW() " +
            "WHERE order_id = #{orderId} AND status = #{oldStatus}")
    int updateForUpstreamRetry(@Param("orderId") String orderId, @Param("payload") String payload, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus);

    @Update("UPDATE invoice_record SET status = #{newStatus}, retry_count = retry_count + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{oldStatus} AND retry_count < #{maxRetry}")
    int updateForAutoRetry(@Param("id") Long id, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus, @Param("maxRetry") int maxRetry);

    // 查询停留在 WAIT_CALLBACK 超过 10 分钟的单据，用于主动查单补偿
    @Select("SELECT * FROM invoice_record WHERE status IN ('WAIT_CALLBACK', 'RED_WAIT_CALLBACK') AND update_time <= #{threshold} LIMIT #{limit}")
    List<InvoiceRecord> selectStuckWaitCallbackRecords(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
    
    @Select("SELECT * FROM invoice_record WHERE status IN ('FAIL', 'RED_FAIL') AND retry_count < #{maxRetry} LIMIT #{limit}")
    List<InvoiceRecord> selectPendingAutoRetryRecords(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Update("UPDATE invoice_record SET update_time = NOW() WHERE id = #{id}")
    void refreshUpdateTime(@Param("id") Long id);

    /**
    * 阶梯式自动重试查询核心 SQL
    * 策略:
    * retry_count = 0 -> 1分钟后重试
    * retry_count = 1 -> 5分钟后重试
    * retry_count = 2 -> 30分钟后重试
    */
    @Select("SELECT * FROM invoice_record " +
    "WHERE status IN ('FAIL', 'RED_FAIL') " +
    "AND retry_count < 3 " +
    "AND ( " +
    "  (retry_count = 0 AND update_time <= DATE_SUB(NOW(), INTERVAL 1 MINUTE)) OR " +
    "  (retry_count = 1 AND update_time <= DATE_SUB(NOW(), INTERVAL 5 MINUTE)) OR " +
    "  (retry_count = 2 AND update_time <= DATE_SUB(NOW(), INTERVAL 30 MINUTE)) " +
    ") LIMIT #{limit}")
    List<InvoiceRecord> selectSmartAutoRetryRecords(@Param("limit") int limit);
}


todo:1、那怎么停止消费和在线改限流量？
2、各种appId对应一下，erp调用中间件 只用time加密合适吗


一版 只做了幂等
二版 做了限流和重试
三版做多租户和鉴权
