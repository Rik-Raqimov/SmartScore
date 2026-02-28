package az.edu.itbrains.SmartScore.dtos.user;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisHistoryItemDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor @NoArgsConstructor
public class UserProfileDto {
    private int totalAnalyses;
    private String lastResult;
    private String lastAnalysisDate;

    private List<AnalysisHistoryItemDto> history;
}
