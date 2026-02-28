package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.dtos.siteContentİtem.SiteContentItemDto;
import az.edu.itbrains.SmartScore.dtos.siteSection.SiteSectionDto;
import az.edu.itbrains.SmartScore.models.SiteContentItem;
import az.edu.itbrains.SmartScore.models.SiteSection;
import az.edu.itbrains.SmartScore.repositories.SiteSectionRepository;
import az.edu.itbrains.SmartScore.services.SiteSectionService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SiteSectionServiceImpl implements SiteSectionService {

    private final SiteSectionRepository sectionRepository;

    @Override
    public List<SiteSectionDto> getAll() {
        return sectionRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public SiteSectionDto getByKey(String key) {
        return sectionRepository.findBySectionKey(key)
                .map(this::convertToDto)
                .orElse(null);
    }

    @Override
    @Transactional
    public boolean save(SiteSectionDto dto) {
        try {
            SiteSection entity = sectionRepository.findBySectionKey(dto.getSectionKey())
                    .orElse(new SiteSection());

            entity.setSectionKey(dto.getSectionKey());
            entity.setTitle(dto.getTitle());

            if (entity.getItems() != null) {
                entity.getItems().clear();
            } else {
                entity.setItems(new ArrayList<>());
            }

            if (dto.getItems() != null) {
                for (SiteContentItemDto itemDto : dto.getItems()) {
                    SiteContentItem item = new SiteContentItem();
                    item.setText(itemDto.getText());
                    item.setOrderIndex(itemDto.getOrderIndex());
                    item.setSection(entity); // Устанавливаем обратную связь
                    entity.getItems().add(item);
                }
            }

            sectionRepository.save(entity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @Transactional
    public boolean delete(String key) {
        try {
            return sectionRepository.findBySectionKey(key)
                    .map(section -> {
                        sectionRepository.delete(section);
                        return true;
                    }).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private SiteSectionDto convertToDto(SiteSection entity) {
        SiteSectionDto dto = new SiteSectionDto();
        dto.setSectionKey(entity.getSectionKey());
        dto.setTitle(entity.getTitle());

        if (entity.getItems() != null) {
            dto.setItems(entity.getItems().stream().map(item -> {
                SiteContentItemDto itemDto = new SiteContentItemDto();
                itemDto.setText(item.getText());
                itemDto.setOrderIndex(item.getOrderIndex());
                return itemDto;
            }).collect(Collectors.toList()));
        }

        return dto;
    }
}