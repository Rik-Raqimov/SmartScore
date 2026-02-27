package az.edu.itbrains.SmartScore.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "site_sections")
public class SiteSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sectionKey;

    private String title;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL)
    private List<SiteContentItem> items;
}