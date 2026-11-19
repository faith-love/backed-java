-- MySQL 8.0.16+ / InnoDB 教学脚本，仅供阅读，未连接或执行任何数据库。
-- 金额统一使用整数分；生产上线需按业务范围、字符长度和权限单独评审迁移。
-- 这是简化的一次付款模型，切换渠道/多次支付尝试需要独立 payment_attempt 表。

CREATE TABLE payment_order (
    merchant_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    channel ENUM('ALIPAY', 'WECHAT') NOT NULL,
    merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_fen BIGINT NOT NULL COMMENT '服务端定价快照，整数分',
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status ENUM('PENDING', 'PAID', 'CLOSED') NOT NULL DEFAULT 'PENDING',
    provider_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_positive_amount CHECK (amount_fen > 0)
) ENGINE = InnoDB;

CREATE TABLE payment_receipt (
    -- 本教学模型一个商户订单最多接受一个成功支付流水。
    merchant_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    channel ENUM('ALIPAY', 'WECHAT') NOT NULL,
    merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_fen BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 同一个渠道商户下的支付流水不能被两个本地订单重复认领。
    UNIQUE KEY uk_provider_receipt (channel, merchant_id, provider_transaction_id),
    CONSTRAINT fk_receipt_order FOREIGN KEY (merchant_order_no)
        REFERENCES payment_order (merchant_order_no)
) ENGINE = InnoDB;

CREATE TABLE payment_outbox (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    merchant_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    status ENUM('NEW', 'CLAIMED', 'SENT') NOT NULL DEFAULT 'NEW',
    claim_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    -- 回调、主动查单都只能创建同一个业务事件。
    UNIQUE KEY uk_paid_event (merchant_order_no, event_type),
    KEY ix_ready (status, next_attempt_at, lease_until),
    CONSTRAINT fk_outbox_order FOREIGN KEY (merchant_order_no)
        REFERENCES payment_order (merchant_order_no)
) ENGINE = InnoDB;

DELIMITER //

-- 只能从已验签/解密并确认支付成功的适配器调用；SQL 本身不能证明支付真实性。
-- 本过程拥有事务边界，不要放进另一个 Spring 事务再嵌套 START TRANSACTION。
-- 实际 Java 项目可把过程中的 SQL 改成同一 @Transactional 方法中的 Mapper 操作。
CREATE PROCEDURE apply_paid_notification(
    IN p_order VARCHAR(64),
    IN p_channel VARCHAR(16),
    IN p_merchant VARCHAR(64),
    IN p_app VARCHAR(64),
    IN p_amount_fen BIGINT,
    IN p_currency CHAR(3),
    IN p_transaction VARCHAR(128),
    IN p_payment_succeeded BOOLEAN
)
main: BEGIN
    DECLARE v_found BOOLEAN DEFAULT TRUE;
    DECLARE v_channel VARCHAR(16);
    DECLARE v_merchant VARCHAR(64);
    DECLARE v_app VARCHAR(64);
    DECLARE v_amount_fen BIGINT;
    DECLARE v_currency CHAR(3);
    DECLARE v_status VARCHAR(16);
    DECLARE v_transaction VARCHAR(128);
    DECLARE v_changed INT;

    -- SELECT 无结果时不能把 NULL 当作一张有效订单继续处理。
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_found = FALSE;
    -- 唯一键冲突、条件更新失败、outbox 插入失败等一律整体回滚并向应用抛错。
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    -- 行锁让不同应用实例也按同一订单串行核对；数据库是共享协调点。
    SELECT channel, merchant_id, app_id, amount_fen, currency, status, provider_transaction_id
      INTO v_channel, v_merchant, v_app, v_amount_fen, v_currency, v_status, v_transaction
      FROM payment_order
     WHERE merchant_order_no = p_order
     FOR UPDATE;

    IF NOT v_found THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_NOT_FOUND';
    END IF;

    -- 所有字段先核对，再判断重复；<=> 是 NULL 安全相等，身份/币种使用精确比较。
    IF NOT (BINARY v_channel <=> BINARY p_channel)
       OR NOT (BINARY v_merchant <=> BINARY p_merchant)
       OR NOT (BINARY v_app <=> BINARY p_app)
       OR NOT (v_amount_fen <=> p_amount_fen)
       OR NOT (BINARY v_currency <=> BINARY p_currency)
       OR NOT (p_payment_succeeded <=> TRUE)
       OR p_transaction IS NULL OR CHAR_LENGTH(TRIM(p_transaction)) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PAYMENT_FIELDS_MISMATCH';
    END IF;

    IF v_status = 'PAID' THEN
        IF NOT (BINARY v_transaction <=> BINARY p_transaction) THEN
            -- 不同成功流水不属于同一通知重投，转入对账/退款处理，不重复履约。
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'DIFFERENT_SUCCESSFUL_PAYMENT';
        END IF;
        COMMIT;
        SELECT 'DUPLICATE' AS result;
        LEAVE main;
    END IF;

    IF v_status <> 'PENDING' THEN
        -- 关闭后收到成功通知需要主动查单和人工/补偿流程，不能静默丢弃。
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CLOSED_ORDER_RECONCILIATION_REQUIRED';
    END IF;

    -- 全局唯一约束同时防止同一渠道流水被另一张本地订单认领。
    INSERT INTO payment_receipt
        (merchant_order_no, channel, merchant_id, provider_transaction_id, amount_fen, currency)
    VALUES (p_order, p_channel, p_merchant, p_transaction, p_amount_fen, p_currency);

    UPDATE payment_order
       SET status = 'PAID', provider_transaction_id = p_transaction, paid_at = UTC_TIMESTAMP(6)
     WHERE merchant_order_no = p_order
       AND status = 'PENDING'
       AND amount_fen = p_amount_fen
       AND currency = p_currency;

    -- 条件更新没有成功就必须抛错回滚，不能留下孤立的收款记录或 outbox。
    SET v_changed = ROW_COUNT();
    IF v_changed <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE_CHANGED';
    END IF;

    -- 和订单、收款记录在同一事务中提交；这里仅存事件，绝不在事务里发 MQ/短信。
    INSERT INTO payment_outbox (event_id, merchant_order_no, event_type, payload)
    VALUES (CONCAT('ORDER_PAID:', p_order), p_order, 'ORDER_PAID',
            JSON_OBJECT('orderNo', p_order, 'amountFen', p_amount_fen, 'currency', p_currency));

    COMMIT;
    -- 应用确认 COMMIT 完成后才按渠道协议 ACK；提交结果未知时不能假定成功。
    SELECT 'APPLIED' AS result;
END//

DELIMITER ;

-- 后台投递流程（伪代码：:参数是应用绑定变量，不是可直接粘贴执行的 SQL）。
-- 1. 短事务领取到期事件，SKIP LOCKED 允许多个实例领取不同任务。
-- START TRANSACTION;
-- SELECT event_id, payload FROM payment_outbox
-- WHERE (status = 'NEW' AND next_attempt_at <= UTC_TIMESTAMP(6))
--    OR (status = 'CLAIMED' AND lease_until < UTC_TIMESTAMP(6))
-- ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED;
-- UPDATE payment_outbox
--    SET status = 'CLAIMED', claim_token = :randomClaimToken,
--        lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND), attempts = attempts + 1
--  WHERE event_id = :selectedEventId;
-- COMMIT;
--
-- 2. 在事务外向 MQ 发布，使用持久化消息并等待 broker 确认。
--    消息携带 event_id；发送耗时超过租期前要续租或取消当前尝试。
--    即便租期设计合理，崩溃窗口仍可能重复投递，因此消费者必须幂等。
--
-- 3. 独立短事务标记 SENT，条件必须包含本次 claim_token，防止旧工作者覆盖新状态。
-- UPDATE payment_outbox SET status = 'SENT', sent_at = UTC_TIMESTAMP(6),
--        claim_token = NULL, lease_until = NULL
--  WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :myClaimToken;
-- 若影响行数不是 1，不能假定自己仍拥有任务，记录租约竞争并查询最新状态。
--
-- 4. 发送失败时用相同 token 条件安排下一次退避重试；进程崩溃则由租期回收。
-- UPDATE payment_outbox SET status = 'NEW', next_attempt_at = :retryAfter,
--        claim_token = NULL, lease_until = NULL
--  WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :myClaimToken;
-- 持续失败需要告警/人工处理，不能丢弃任务或无限密集重试。
--
-- 5. 消费者数据库：inbox(event_id PRIMARY KEY) 与实际履约变更一起提交。
--    收到重复 event_id 时核对已提交结果，不重复发货/加积分；不要先记 inbox 再开事务履约。
--
-- 查单/关单：外部查询与关闭调用均在数据库事务外执行。
-- 查单已成功：验真并标准化后复用 apply_paid_notification。
-- 关单明确成功/渠道明确未付款关闭：用 WHERE status = 'PENDING' 条件转 CLOSED。
-- 超时或结果未知：保留待查状态并重查，不得直接改 CLOSED，也不得重建新订单重复扣款。
-- 条件更新影响 0 行时重新读取，若 PAID 则走已付款流程，不用旧状态强行覆盖。
