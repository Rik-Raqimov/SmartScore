package az.edu.itbrains.SmartScore.dtos.siteSection;

import az.edu.itbrains.SmartScore.dtos.siteContentİtem.SiteContentItemDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteSectionDto {
    private String sectionKey;
    private String title;
    private List<SiteContentItemDto> items;
}