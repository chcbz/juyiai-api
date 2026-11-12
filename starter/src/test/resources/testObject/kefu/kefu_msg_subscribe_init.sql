-- 初始化客服消息订阅数据
truncate table kefu_msg_subscribe;
INSERT INTO kefu_msg_subscribe (id, client_id, type_code, jiacn, wx_rx_flag, sms_rx_flag, status, create_time, update_time)
VALUES (1, 'fixture-client_id-1', 'gift_usage', 'fixture-jiacn-2', 1, 0, 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_subscribe (id, client_id, type_code, jiacn, wx_rx_flag, sms_rx_flag, status, create_time, update_time)
VALUES (2, 'fixture-client_id-1', 'task', 'fixture-jiacn-2', 1, 0, 1, 1584175547, 1584177170);