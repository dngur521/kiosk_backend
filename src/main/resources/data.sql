-- 1. 메뉴 데이터 삽입 (menus)
-- 커피류
INSERT INTO menus (name, price, category) VALUES ('아이스 아메리카노', 4500, 'Coffee');
INSERT INTO menus (name, price, category) VALUES ('따뜻한 아메리카노', 4000, 'Coffee');
INSERT INTO menus (name, price, category) VALUES ('카페라떼', 5000, 'Coffee');
INSERT INTO menus (name, price, category) VALUES ('바닐라 라떼', 5500, 'Coffee');
INSERT INTO menus (name, price, category) VALUES ('카푸치노', 5000, 'Coffee');

-- 논커피 및 티
INSERT INTO menus (name, price, category) VALUES ('딸기 라떼', 6000, 'Non-Coffee');
INSERT INTO menus (name, price, category) VALUES ('레몬 에이드', 5500, 'Non-Coffee');
INSERT INTO menus (name, price, category) VALUES ('얼그레이 티', 4500, 'Tea');

-- 디저트
INSERT INTO menus (name, price, category) VALUES ('초코 브라우니', 3500, 'Dessert');
INSERT INTO menus (name, price, category) VALUES ('플레인 스콘', 3000, 'Dessert');

-- 2. 유의어 및 방언 데이터 삽입 (menu_synonyms) 
-- 아이스 아메리카노 (ID: 1)
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (1, '아아');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (1, '차가운 아메리카노');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (1, '아이스 아메');

-- 따뜻한 아메리카노 (ID: 2)
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (2, '뜨아');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (2, '따아');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (2, '따수운 아메리카노');

-- 카페라떼 (ID: 3)
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (3, '라떼');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (3, '커피우유'); -- 노인분들 표현 예시

-- 바닐라 라떼 (ID: 4)
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (4, '바닐라');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (4, '바라');

-- 딸기 라떼 (ID: 6)
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (6, '딸기우유');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (6, '딸기드링크');

-- 방언 및 모호한 표현 예시 [cite: 13, 19]
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (1, '시원한거 한잔');
INSERT INTO menu_synonyms (menu_id, synonym) VALUES (2, '뜨끈한거 하나');