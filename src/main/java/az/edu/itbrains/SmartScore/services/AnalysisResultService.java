package az.edu.itbrains.SmartScore.services;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisResultDto;
import az.edu.itbrains.SmartScore.models.User;
import org.springframework.web.multipart.MultipartFile;

public interface AnalysisResultService {
    AnalysisResultDto calculateScore(User user);

    AnalysisResultDto processAndAnalyze(MultipartFile file);

    AnalysisResultDto getLatestResultForUser();
}
