package com.kemini.kiosk_backend.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.entity.MenuCategory;
import com.kemini.kiosk_backend.domain.repository.MenuCategoryRepository;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.dto.request.MenuRequestDto;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;
    private final MenuCategoryRepository categoryRepository;

    private final String UPLOAD_DIR = System.getProperty("user.home") + File.separator + "kiosk_uploads" + File.separator + "menu" + File.separator;
    private final String BASE_URL = "https://kemini-kiosk-api.duckdns.org";
    private final String DEFAULT_IMAGE = "default.png";

    @Transactional
    public MenuResponseDto saveMenu(MenuRequestDto dto) throws IOException {
        MenuCategory category = categoryRepository.findById(dto.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("카테고리가 없습니다."));

        // 1. UUID를 활용한 파일 이름 충돌 방지 로직
        String imageName = DEFAULT_IMAGE;
        if (dto.getImageFile() != null && !dto.getImageFile().isEmpty()) {
            imageName = saveFileWithUuid(dto.getImageFile());
        }

        Menu menu = Menu.builder()
                .name(dto.getName())
                .price(dto.getPrice())
                .description(dto.getDescription())
                .imageName(imageName)
                .category(category)
                .build();

        return new MenuResponseDto(menuRepository.save(menu), BASE_URL);
    }

    public List<MenuResponseDto> getMenus(Long categoryId) {
        List<Menu> menus = (categoryId == null) 
                ? menuRepository.findAllByOrderByIdAsc()
                : menuRepository.findByCategoryIdOrderByIdAsc(categoryId);
        
        return menus.stream()
                .map(m -> new MenuResponseDto(m, BASE_URL))
                .collect(Collectors.toList());
    }

    @Transactional
    public MenuResponseDto updateMenu(Long id, MenuRequestDto dto) throws IOException {
        // 1. 기존 메뉴 조회
        Menu menu = menuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("수정할 메뉴가 존재하지 않습니다. ID: " + id));

        // 2. 카테고리 변경 시 확인
        if (dto.getCategoryId() != null) {
            MenuCategory category = categoryRepository.findById(dto.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 카테고리입니다."));
            menu.setCategory(category);
        }

        // 3. 이미지 수정 로직
        if (dto.getImageFile() != null && !dto.getImageFile().isEmpty()) {
            // 기존 파일이 기본 이미지가 아니라면 서버에서 삭제
            if (!menu.getImageName().equals(DEFAULT_IMAGE)) {
                File oldFile = new File(UPLOAD_DIR + menu.getImageName());
                if (oldFile.exists()) oldFile.delete();
            }
            // 새 파일 저장 (UUID 적용된 새 이름 반환)
            String newImageName = saveFileWithUuid(dto.getImageFile());
            menu.setImageName(newImageName);
        }

        // 4. 나머지 필드 업데이트
        menu.setName(dto.getName());
        menu.setPrice(dto.getPrice());
        menu.setDescription(dto.getDescription());

        return new MenuResponseDto(menu, BASE_URL);
    }

    @Transactional
    public void deleteMenu(Long id) {
        Menu menu = menuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("메뉴가 없습니다."));
        
        // 서버에서 물리 파일도 삭제 (기본 이미지 제외)
        if (!menu.getImageName().equals(DEFAULT_IMAGE)) {
            new File(UPLOAD_DIR + menu.getImageName()).delete();
        }
        menuRepository.delete(menu);
    }

    // 파일 저장 로직 (UUID 적용)
    private String saveFileWithUuid(MultipartFile file) throws IOException {
        File folder = new File(UPLOAD_DIR);
        if (!folder.exists()) folder.mkdirs();

        // UUID 생성 + 원본 확장자 추출
        String uuid = UUID.randomUUID().toString();
        String originalName = file.getOriginalFilename();
        String extension = originalName.substring(originalName.lastIndexOf("."));
        String savedName = uuid + "_" + originalName; // 예: 550e8400-e29b..._americano.jpg

        Path path = Paths.get(UPLOAD_DIR + savedName);
        Files.write(path, file.getBytes());

        return savedName;
    }
}