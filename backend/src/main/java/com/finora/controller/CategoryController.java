package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CategoryDto;
import com.finora.repository.CategoryRepository;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final CurrentUser currentUser;

    public CategoryController(CategoryRepository categoryRepository, CurrentUser currentUser) {
        this.categoryRepository = categoryRepository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<CategoryDto>> list() {
        var categories = categoryRepository.findByUserId(currentUser.id()).stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem()))
                .toList();
        return ApiResponse.ok(categories);
    }
}
