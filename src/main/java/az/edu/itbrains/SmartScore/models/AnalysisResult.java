package az.edu.itbrains.SmartScore.models;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "analysis_result")
public class AnalysisResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer score;

    private Integer incomeStability;

    private Integer expenseControl;

    private Integer balanceDynamics;

    private Integer paymentHistory;

    private Integer periodMonths;

    private Date calculatedAt;

    @ManyToOne
    private User user;

}
