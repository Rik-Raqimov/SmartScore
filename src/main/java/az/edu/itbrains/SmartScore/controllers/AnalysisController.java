package az.edu.itbrains.SmartScore.controllers;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisResultDto;
import az.edu.itbrains.SmartScore.services.AnalysisResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@CrossOrigin
public class AnalysisController {

    private final AnalysisResultService analysisService;

    @PostMapping("/upload")
    public ResponseEntity<AnalysisResultDto> uploadFile(@RequestParam("file") MultipartFile file) {
        AnalysisResultDto result = analysisService.processAndAnalyze(file);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/latest")
    public ResponseEntity<AnalysisResultDto> getLatestResult() {
        AnalysisResultDto result = analysisService.getLatestResultForUser();
        return ResponseEntity.ok(result);
    }
}