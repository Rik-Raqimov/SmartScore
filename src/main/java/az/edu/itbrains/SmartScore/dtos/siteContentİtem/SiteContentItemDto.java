package az.edu.itbrains.SmartScore.dtos.siteContentİtem;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteContentItemDto {
    private String text;
    private Integer orderIndex;
}