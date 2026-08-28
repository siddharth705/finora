package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CategoryDto;
import com.finora.dto.CategoryOptionsDto;
import com.finora.dto.CategoryUsageDto;
import com.finora.repository.CategoryRepository;
import com.finora.security.CurrentUser;
import com.finora.service.CategoryService;
import com.finora.util.CategoryPalette;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final CurrentUser currentUser;

    public CategoryController(CategoryRepository categoryRepository, CategoryService categoryService,
                               CurrentUser currentUser) {
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    public record CreateCategoryRequest(String name, String icon, String color) {}

    public record UpdateCategoryRequest(String name, String icon, String color) {}

    @GetMapping
    public ApiResponse<List<CategoryDto>> list() {
        var categories = categoryRepository.findByUserId(currentUser.id()).stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()))
                .toList();
        return ApiResponse.ok(categories);
    }

    @GetMapping("/options")
    public ApiResponse<CategoryOptionsDto> options() {
        var icons = CategoryPalette.ICONS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        var colors = CategoryPalette.COLORS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        return ApiResponse.ok(new CategoryOptionsDto(icons, colors));
    }

    @PostMapping
    public ApiResponse<CategoryDto> create(@RequestBody CreateCategoryRequest request) {
        var c = categoryService.create(currentUser.id(), request.name(), request.icon(), request.color());
        return ApiResponse.ok(new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CategoryDto> update(@PathVariable java.util.UUID id,
                                            @RequestBody UpdateCategoryRequest request) {
        var c = categoryService.rename(currentUser.id(), id, request.name(), request.icon(), request.color());
        return ApiResponse.ok(new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<CategoryUsageDto> usage(@PathVariable java.util.UUID id) {
        return ApiResponse.ok(categoryService.usage(currentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable java.util.UUID id,
                                     @RequestParam(required = false) java.util.UUID reassignTo) {
        categoryService.delete(currentUser.id(), id, reassignTo);
        return ApiResponse.ok(null);
    }
}
