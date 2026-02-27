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

    private Integer incomeStability; // Gəlir Stabilliyi

    private Integer expenseControl;    // Xərc İdarəsi

    private Integer balanceDynamics;   // Balans Dinamikası

    private Integer paymentHistory;    // Ödəniş Tarixçəsi

    private Integer periodMonths;

}
