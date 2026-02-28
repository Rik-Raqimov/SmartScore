package az.edu.itbrains.SmartScore.dtos.analysisResult;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor @NoArgsConstructor
public class AnalysisHistoryItemDto {
    private String date;
    private String time;
    private int score;
    private String status;
}

