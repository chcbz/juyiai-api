-- 初始化微信模板数据
truncate table wx_mp_template;
INSERT INTO wx_mp_template (template_id, client_id, appid, title, primary_industry, deputy_industry, content, example, status, create_time, update_time)
VALUES ('fixture-wx_template_id-3', 'fixture-client_id-1', 'fixture-appid-8', '工作提醒', '金融业', '保险',
        '{{first.DATA}}\n\n提醒类型：{{keyword1.DATA}}\n\n提醒内容：{{keyword2.DATA}}\n\n{{remark.DATA}}', NULL, 1, 1, 1);