package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.dtos.transaction.TransactionDto;
import az.edu.itbrains.SmartScore.models.Transaction;
import az.edu.itbrains.SmartScore.services.GptService;
import az.edu.itbrains.SmartScore.services.PdfService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PdfServiceImpl implements PdfService {

    private final GptService gptService;
    private final ModelMapper modelMapper; // Нужен для превращения DTO от ИИ в Entity для базы

    @Override
    public String extractText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return "Xəta: Fayl tapılmadı";
        try (PDDocument document = Loader.loadPDF(file)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            return "Xəta: " + e.getMessage();
        }
    }

    @Override
    public List<Transaction> parseTransactionsWithAi(String rawText) {
        // Вызываем твой новый метод из GptService, который сразу дает список Entity
        return gptService.analyzeStatementAndGetTransactions(rawText);
    }
}