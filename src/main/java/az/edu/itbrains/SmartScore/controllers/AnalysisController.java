package az.edu.itbrains.SmartScore.controllers;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisResultDto;
import az.edu.itbrains.SmartScore.services.AnalysisResultService;
import az.edu.itbrains.SmartScore.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@CrossOrigin
public class AnalysisController {

    private final AnalysisResultService analysisService;
    private final UserService userService;


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

    @GetMapping("/check-status")
    public ResponseEntity<?> checkUserStatus() {
        var user = userService.getCurrentUser();
        if (user == null) {
            return ResponseEntity.ok(Map.of(
                    "registered", false,
                    "action", "REDIRECT_TO_UPLOAD",
                    "message", "Zəhmət olmasa, bank çıxarışınızı yükləyin."
            ));
        }
        return ResponseEntity.ok(Map.of("registered", true, "action", "SHOW_SCORE"));
    }
}