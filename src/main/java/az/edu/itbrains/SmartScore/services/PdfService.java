package az.edu.itbrains.SmartScore.services;

import az.edu.itbrains.SmartScore.models.Transaction;

import java.util.List;

public interface PdfService {
    String extractText(String filePath);

    List<Transaction> parseTransactionsWithAi(String rawText);
}
