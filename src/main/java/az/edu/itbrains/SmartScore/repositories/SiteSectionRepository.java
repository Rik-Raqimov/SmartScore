package az.edu.itbrains.SmartScore.repositories;

import az.edu.itbrains.SmartScore.models.SiteSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteSectionRepository extends JpaRepository<SiteSection, Long> {
    Optional<SiteSection> findBySectionKey(String key);
}
