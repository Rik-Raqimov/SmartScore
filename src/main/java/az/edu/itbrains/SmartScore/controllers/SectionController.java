package az.edu.itbrains.SmartScore.controllers;

import az.edu.itbrains.SmartScore.dtos.siteSection.SiteSectionDto;
import az.edu.itbrains.SmartScore.services.SiteSectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sections")
@RequiredArgsConstructor
@CrossOrigin
public class SectionController {

    private final SiteSectionService sectionService;

    @GetMapping("/all")
    public List<SiteSectionDto> getAll() {
        return sectionService.getAll();
    }

    @GetMapping("/{key}")
    public ResponseEntity<SiteSectionDto> getByKey(@PathVariable String key) {
        SiteSectionDto dto = sectionService.getByKey(key);
        return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.notFound().build();
    }

    @PostMapping("/save")
    public ResponseEntity<String> save(@RequestBody SiteSectionDto dto) {
        boolean result = sectionService.save(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body("Seksiya uğurla yaradıldı/yeniləndi");
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<String> delete(@PathVariable String key) {
        boolean result = sectionService.delete(key);
        return ResponseEntity.ok("Seksiya silindi");
    }


}