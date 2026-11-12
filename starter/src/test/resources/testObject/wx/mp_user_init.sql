-- 初始化微信用户数据
truncate table wx_mp_user;
INSERT INTO wx_mp_user (id, client_id, appid, subscribe, open_id, jiacn, subscribe_time, email, sex, language, nickname, head_img_url, city, country, province, union_id, group_id, subscribe_scene, qr_scene, qr_scene_str, subscribe_items, status, remark, create_time, update_time)
VALUES (2, 'fixture-client_id-1', 'fixture-appid-8', 1, 'fixture-jiacn-2', 'fixture-jiacn-2', NULL, NULL, 1, NULL, '陈惠超',
        'https://example.com/fixture-9.png',
        '东莞', '中国', '广东', NULL, NULL, NULL, NULL, NULL, 'vote', 1, NULL, 1595131250, 20200723010937);