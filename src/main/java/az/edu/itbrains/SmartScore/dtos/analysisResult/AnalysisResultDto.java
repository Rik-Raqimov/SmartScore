package az.edu.itbrains.SmartScore.dtos.analysisResult;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AnalysisResultDto {

    private Integer score;

    private Integer incomeStability;

    private Integer expenseControl;

    private Integer balanceDynamics;

    private Integer paymentHistory;

    private Integer periodMonths;

}
