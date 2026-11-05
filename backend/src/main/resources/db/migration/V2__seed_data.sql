-- V2：种子数据（docs/database.md §5，F-13）。
-- 封面 / 预置结果图文件放在 backend/src/main/resources/seed/（T-021），通过 /files/seed/** 访问。

-- ---------- 分类（"全部"由前端虚拟，不是记录） ----------
INSERT INTO categories (id, name, sort_order) VALUES
    ('creative', '创意图片', 10),
    ('portrait', '人像写真', 20),
    ('festival', '节日贺卡', 30);

-- ---------- 模板：张数覆盖 1 / 2 / 3；一个只接受 PNG；一个上限 1 MB ----------
INSERT INTO templates (id, category_id, title, description, cover_url, sample_result_path,
                       required_image_count, accepted_mime_types, max_file_size_bytes, sort_order)
VALUES
    ('demo-postcard', 'creative', '旅行明信片', '上传图片，模拟生成一张明信片',
     '/files/seed/covers/postcard.jpg', 'seed/results/postcard.png',
     2, JSON_ARRAY('image/jpeg', 'image/png'), 5242880, 10),
    ('creative-poster', 'creative', '创意海报', '上传一张图片，模拟生成风格化海报',
     '/files/seed/covers/poster.jpg', 'seed/results/poster.png',
     1, JSON_ARRAY('image/jpeg', 'image/png', 'image/webp'), 5242880, 20),
    ('portrait-id-photo', 'portrait', '证件照', '上传一张 PNG 人像照片（不超过 1 MB），模拟生成证件照',
     '/files/seed/covers/id-photo.jpg', 'seed/results/id-photo.png',
     1, JSON_ARRAY('image/png'), 1048576, 10),
    ('festival-collage', 'festival', '节日拼图贺卡', '上传三张图片，模拟拼合成一张节日贺卡',
     '/files/seed/covers/collage.jpg', 'seed/results/collage.png',
     3, JSON_ARRAY('image/jpeg', 'image/png', 'image/webp'), 5242880, 10);

-- ---------- 演示账户（密码见 README，仅供演示；表中只存 BCrypt 哈希，cost 10） ----------
INSERT INTO users (username, email, password_hash) VALUES
    ('demo1', 'demo1@example.com', '$2a$10$BpxBLDiVUYoM.u0vpiOWEuR1mpe3GVxuFy0hTByNsvFr8zfdU1Ts6'),
    ('demo2', 'demo2@example.com', '$2a$10$RJ1kgqcg41xUxBXmmkw0Vempz/wtqUEg6QUoRdD2G6gxChk0hqjhe');
