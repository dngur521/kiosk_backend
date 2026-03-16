package com.kemini.kiosk_backend.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.MenuCategory;
import com.kemini.kiosk_backend.domain.repository.MenuCategoryRepository;
import com.kemini.kiosk_backend.dto.request.CategoryRequestDto;
import com.kemini.kiosk_backend.dto.response.CategoryResponseDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final MenuCategoryRepository categoryRepository;

    // CREATE
    @Transactional
    public CategoryResponseDto saveCategory(CategoryRequestDto dto) {
        if (categoryRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("이미 존재하는 카테고리 이름입니다.");
        }
        MenuCategory category = MenuCategory.builder()
                .name(dto.getName())
                .build();
        return new CategoryResponseDto(categoryRepository.save(category));
    }

    // READ (All)
    public List<CategoryResponseDto> getAllCategories() {
        return categoryRepository.findAllByOrderByIdAsc().stream()
                .map(CategoryResponseDto::new)
                .collect(Collectors.toList());
    }

    // DELETE
    @Transactional
    public void deleteCategory(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new IllegalArgumentException("삭제할 카테고리가 존재하지 않습니다.");
        }
        categoryRepository.deleteById(id);
    }
}