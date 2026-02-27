package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.dtos.transaction.TransactionDto;
import az.edu.itbrains.SmartScore.enums.CategoryType;
import az.edu.itbrains.SmartScore.models.Transaction;
import az.edu.itbrains.SmartScore.services.GptService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class GptServiceImpl implements GptService {

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public GptServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    private static final String SYSTEM_PROMPT_TEMPLATE = """
    You are a professional bank statement data extractor.
    Extract EVERY transaction line. 

    CRITICAL DATE & TIME RULES:
    1. YEARS: Use ONLY the years found in the text: %s.
    2. TIME: You MUST extract the exact time (HH:mm:ss) for each row (e.g., 21:06:34).
    3. FORMAT: operationDate must be "yyyy-MM-ddTHH:mm:ss".
    
    DUPLICATE RULE:
    - If there are multiple identical amounts on the same day (e.g., three "+20.00"), you MUST extract each one separately. DO NOT skip any.

    CATEGORIZATION:
    - "+" amounts = "INCOME".
    - "-" amounts = "DAILY", "ESSENTIAL" (P2P, bills, ATM), or "CREDIT".
    CRITICAL RESTRICTION: Use "CREDIT" ONLY for official bank loan payments. DO NOT use "CREDIT" for rent (e.g., "ICARA"), subscriptions, or regular transfers. If in doubt, use "ESSENTIAL" or "DAILY".

    JSON STRUCTURE (MANDATORY):
    Return ONLY a valid JSON array of objects with these exact keys:
    - "amount": decimal number (positive for income, negative for expense).
    - "description": string (ACTUAL merchant name or transaction text from the statement). MUST NOT BE NULL.
    - "category": string (INCOME, DAILY, ESSENTIAL, or CREDIT).
    - "operationDate": string (yyyy-MM-ddTHH:mm:ss).

    Example:
    [
      {"amount": 20.00, "description": "www.birbank.az", "category": "INCOME", "operationDate": "2025-11-30T21:06:34"},
      {"amount": 400.00, "description": "eManat", "category": "INCOME", "operationDate": "2025-11-30T22:15:00"}
    ]
    
    Return ONLY the JSON array. Do not include markdown or text.
    """;

    @Override
    public List<TransactionDto> analyzeStatement(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) return List.of();

        String documentYears = extractYearsFromText(extractedText);
        String dynamicPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, documentYears);

        List<String> chunks = splitByNewlineWithOverlap(extractedText, 2000, 400);
        List<TransactionDto> allRaw = new ArrayList<>();

        for (String part : chunks) {
            try {
                System.out.println("LOG: Отправляю запрос в OpenAI..."); // Проверка начала

                String raw = chatClient.prompt()
                        .system(dynamicPrompt)
                        .user(part)
                        .call()
                        .content();

                System.out.println("LOG AI RESPONSE: " + raw); // Проверка ответа

                List<TransactionDto> res = safeParse(extractJsonArray(raw));
                if (res != null) allRaw.addAll(res);
            } catch (Exception e) {
                System.err.println("!!! КРИТИЧЕСКАЯ ОШИБКА ИИ !!!");
                e.printStackTrace();
            }
        }

        allRaw = cleanUpAIErrors(allRaw);
        return dedupe(allRaw);
    }

    @Override
    public List<Transaction> analyzeStatementAndGetTransactions(String rawText) {
        // 1. Вызываем уже готовый метод анализа, который возвращает DTO
        List<TransactionDto> dtos = analyzeStatement(rawText);

        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }

        // 2. Превращаем DTO в Entity (Transaction) для сохранения в БД
        List<Transaction> transactions = new ArrayList<>();

        for (TransactionDto dto : dtos) {
            Transaction tx = new Transaction();
            tx.setAmount(dto.getAmount());
            tx.setDescription(dto.getDescription());
            tx.setCategory(dto.getCategory());

            // Превращаем строку даты "yyyy-MM-ddTHH:mm:ss" в Date
            if (dto.getOperationDate() != null) {
                try {
                    java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(dto.getOperationDate());
                    java.util.Date date = java.util.Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
                    tx.setOperationDate(date);
                } catch (Exception e) {
                    System.err.println("Дата парсинг xətası: " + e.getMessage());
                }
            }
            transactions.add(tx);
        }

        return transactions;
    }

    private List<TransactionDto> cleanUpAIErrors(List<TransactionDto> list) {
        for (TransactionDto tx : list) {
            if (tx.getAmount() == null) continue;

            if (tx.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                tx.setCategory(CategoryType.INCOME);
            } else {
                if (tx.getCategory() == CategoryType.INCOME) {
                    tx.setCategory(CategoryType.DAILY);
                }
            }
        }
        return list;
    }

    private List<TransactionDto> dedupe(List<TransactionDto> list) {
        Map<String, TransactionDto> map = new LinkedHashMap<>();

        for (TransactionDto t : list) {
            if (t == null || t.getAmount() == null) continue;

            // ✅ ИСПРАВЛЕНИЕ: Берем дату ВМЕСТЕ СО ВРЕМЕНЕМ.
            // Иначе три платежа по 20 манат в один день склеятся в один и убьют твои 38%!
            String opDate = t.getOperationDate() != null ? t.getOperationDate() : "no-date";
            String amt = t.getAmount().stripTrailingZeros().toPlainString();
            String rawDesc = t.getDescription() != null ? t.getDescription().toLowerCase().trim() : "no-desc";

            String shortDesc = rawDesc.length() > 7 ? rawDesc.substring(0, 7) : rawDesc;

            String key = opDate + "|" + amt + "|" + shortDesc;

            map.putIfAbsent(key, t);
        }
        return new ArrayList<>(map.values());
    }

    private String extractYearsFromText(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("20[2-3][0-9]").matcher(text);
        Set<String> years = new HashSet<>();
        while (m.find()) years.add(m.group());
        return years.isEmpty() ? "current year" : String.join(", ", years);
    }

    private List<String> splitByNewlineWithOverlap(String text, int limit, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            chunks.add(text.substring(start, end));
            if (end == text.length()) break;
            start = end - overlap;
        }
        return chunks;
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        return (start != -1 && end != -1) ? raw.substring(start, end + 1) : "[]";
    }

    private List<TransactionDto> safeParse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}