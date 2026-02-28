package az.edu.itbrains.SmartScore.controllers;

import az.edu.itbrains.SmartScore.dtos.user.UserProfileDto;
import az.edu.itbrains.SmartScore.services.AnalysisResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-profile/")
@RequiredArgsConstructor
@CrossOrigin
public class UserProfileController {
    private final AnalysisResultService analysisResultService;

    @GetMapping("stats")
    public ResponseEntity<UserProfileDto> getUserProfile() {
        UserProfileDto profile = analysisResultService.getUserProfileData();
        return ResponseEntity.ok(profile);
    }
}
