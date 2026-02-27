package az.edu.itbrains.SmartScore.repositories;

import az.edu.itbrains.SmartScore.models.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult,Long> {
    Optional<Object> findTopByUserIdOrderByCalculatedAtDesc(Long id);
}
