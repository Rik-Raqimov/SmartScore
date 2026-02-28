package az.edu.itbrains.SmartScore.services;

import az.edu.itbrains.SmartScore.dtos.siteSection.SiteSectionDto;

import java.util.List;

public interface SiteSectionService {
    List<SiteSectionDto> getAll();

    SiteSectionDto getByKey(String key);

    boolean save(SiteSectionDto dto);

    boolean delete(String key);
}
