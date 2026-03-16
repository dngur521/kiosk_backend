package com.kemini.kiosk_backend.dto.response; // 패키지 경로 확인

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CartItem implements java.io.Serializable{
    private static final long serialVersionUID = 1L; // 버전 관리용 ID 추가
    private Long menuId;
    private String menuName;
    private Integer price;
    private Integer quantity;
}