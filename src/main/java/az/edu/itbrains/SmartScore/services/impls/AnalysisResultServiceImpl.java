package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisResultDto;
import az.edu.itbrains.SmartScore.enums.CategoryType;
import az.edu.itbrains.SmartScore.models.AnalysisResult;
import az.edu.itbrains.SmartScore.models.StatementFile;
import az.edu.itbrains.SmartScore.models.Transaction;
import az.edu.itbrains.SmartScore.models.User;
import az.edu.itbrains.SmartScore.repositories.AnalysisResultRepository;
import az.edu.itbrains.SmartScore.repositories.StatementFileRepository;
import az.edu.itbrains.SmartScore.repositories.TransactionRepository;
import az.edu.itbrains.SmartScore.services.AnalysisResultService;
import az.edu.itbrains.SmartScore.services.GptService;
import az.edu.itbrains.SmartScore.services.PdfService;
import az.edu.itbrains.SmartScore.services.UserService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AnalysisResultServiceImpl implements AnalysisResultService {

    private final ModelMapper modelMapper;
    private final TransactionRepository transactionRepository;
    private final StatementFileRepository statementFileRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final PdfService pdfService;
    private final GptService gptService;
    private final UserService userService;

    private static final Set<CategoryType> EXPENSE_CATS = Set.of(
            CategoryType.DAILY, CategoryType.ESSENTIAL, CategoryType.CREDIT
    );
    private static final Set<CategoryType> OBLIGATION_CATS = Set.of(
            CategoryType.ESSENTIAL, CategoryType.CREDIT
    );

    private static final int CALC_SCALE = 10;

    @Override
    @Transactional
    public AnalysisResultDto calculateScore(User user) {
        StatementFile lastFile = statementFileRepository
                .findTopByUserIdOrderByUploadedAtDesc(user.getId())
                .orElseThrow(() -> new RuntimeException("No file found"));

        List<Transaction> txs = transactionRepository.findAllByStatementFileId(lastFile.getId());
        if (txs == null || txs.isEmpty()) return new AnalysisResultDto();

        List<Transaction> sorted = txs.stream()
                .filter(t -> t.getOperationDate() != null && t.getCategory() != null && t.getAmount() != null)
                .sorted(Comparator.comparing(Transaction::getOperationDate))
                .toList();

        // 1. ИДЕАЛЬНЫЙ СКАНЕР PDF (Никакого ИИ!)
        PdfData pdfData = extractAllFromPdf(lastFile);

        // 2. ДЛЯ ИСТОРИИ ПЛАТЕЖЕЙ ИСПОЛЬЗУЕМ БАЗУ (Там нужны категории)
        Map<YearMonth, MonthAgg> monthMap = buildMonthlyAggregates(sorted);
        List<YearMonth> months;
        BigDecimal[] monthlyIncomeForStability;

        if (!pdfData.monthlyIncomes.isEmpty()) {
            months = new ArrayList<>(pdfData.monthlyIncomes.keySet());
            Collections.sort(months);
            monthlyIncomeForStability = months.stream().map(pdfData.monthlyIncomes::get).toArray(BigDecimal[]::new);
        } else {
            // Резервный вариант, если PDF не прочитался
            months = monthMap.keySet().stream().filter(ym -> monthMap.get(ym).income.compareTo(BigDecimal.ZERO) > 0).sorted().toList();
            monthlyIncomeForStability = months.stream().map(m -> monthMap.get(m).income).toArray(BigDecimal[]::new);
        }

        if (months.isEmpty()) return new AnalysisResultDto();

        // 3. СЧИТАЕМ СКОРИНГ (Используя идеальные цифры из PDF)
        int incomeScore = calcIncomeStability(monthlyIncomeForStability);
        int expenseScore = calcExpenseControl(pdfData.totalIncome, pdfData.totalExpense);
        int balanceScore = calcBalanceDynamicsFromPdf(pdfData.openingBalance, pdfData.closingBalance);
        int paymentScore = calcPaymentHistory(monthMap, months);

        BigDecimal finalScoreRaw =
                BigDecimal.valueOf(incomeScore).multiply(BigDecimal.valueOf(0.25))
                        .add(BigDecimal.valueOf(expenseScore).multiply(BigDecimal.valueOf(0.20)))
                        .add(BigDecimal.valueOf(balanceScore).multiply(BigDecimal.valueOf(0.25)))
                        .add(BigDecimal.valueOf(paymentScore).multiply(BigDecimal.valueOf(0.30)));

        int finalScore = clampInt(finalScoreRaw.setScale(0, RoundingMode.HALF_UP).intValue(), 0, 100);

        System.out.println("\n================ ИТОГОВЫЙ СКОРИНГ ================");
        System.out.println("LOG: Стабильность (" + incomeScore + " * 0.25) = " + (incomeScore * 0.25));
        System.out.println("LOG: Расходы      (" + expenseScore + " * 0.20) = " + (expenseScore * 0.20));
        System.out.println("LOG: Баланс       (" + balanceScore + " * 0.25) = " + (balanceScore * 0.25));
        System.out.println("LOG: История      (" + paymentScore + " * 0.30) = " + (paymentScore * 0.30));
        System.out.println("LOG: ФИНАЛЬНЫЙ СКОР = " + finalScore);
        System.out.println("==================================================\n");

        AnalysisResult entity = new AnalysisResult();
        entity.setUser(user);
        entity.setIncomeStability(incomeScore);
        entity.setExpenseControl(expenseScore);
        entity.setBalanceDynamics(balanceScore);
        entity.setPaymentHistory(paymentScore);
        entity.setScore(finalScore);
        entity.setCalculatedAt(new Date());
        entity.setPeriodMonths(months.size());
        analysisResultRepository.saveAndFlush(entity);

        AnalysisResultDto dto = new AnalysisResultDto();
        dto.setIncomeStability(incomeScore);
        dto.setExpenseControl(expenseScore);
        dto.setBalanceDynamics(balanceScore);
        dto.setPaymentHistory(paymentScore);
        dto.setScore(finalScore);
        dto.setPeriodMonths(months.size());
        return dto;
    }

    @Override
    @Transactional
    public AnalysisResultDto processAndAnalyze(MultipartFile file) {
        try {
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            java.io.File uploadDir = new java.io.File("uploads");
            if (!uploadDir.exists()) uploadDir.mkdirs();

            // Объявляем destination
            java.io.File destination = new java.io.File(uploadDir.getAbsolutePath() + java.io.File.separator + fileName);
            file.transferTo(destination);

            User currentUser = userService.getCurrentUser();
            StatementFile statementFile = new StatementFile();
            statementFile.setOriginalFileName(fileName);
            statementFile.setStoredFilePath(destination.getAbsolutePath());
            statementFile.setFileType("application/pdf");
            statementFile.setStatus(az.edu.itbrains.SmartScore.enums.StatementFileStatus.COMPLETED);
            statementFile.setUser(currentUser);
            statementFile.setUploadedAt(LocalDateTime.now());

            statementFile = statementFileRepository.save(statementFile);

            // --- ТЕПЕРЬ ВСЁ БУДЕТ ВИДНО ---

            // 1. Извлекаем текст
            String rawText = pdfService.extractText(destination.getAbsolutePath());

            // 2. Отправляем в ИИ (теперь gptService виден!)
            List<Transaction> transactions = gptService.analyzeStatementAndGetTransactions(rawText);

            // 3. Сохраняем транзакции
            for (Transaction tx : transactions) {
                tx.setStatementFile(statementFile);
                tx.setUser(currentUser);
            }
            transactionRepository.saveAll(transactions);
            transactionRepository.flush();

            return calculateScore(currentUser);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Xəta: " + e.getMessage());
        }
    }


    @Override
    public AnalysisResultDto getLatestResultForUser() {
        User currentUser = userService.getCurrentUser();
        return analysisResultRepository.findTopByUserIdOrderByCalculatedAtDesc(currentUser.getId())
                .map(entity -> modelMapper.map(entity, AnalysisResultDto.class))
                .orElse(new AnalysisResultDto());
    }

    // ✅ БРОНЕБОЙНЫЙ ХРОНОЛОГИЧЕСКИЙ СКАНЕР PDF
    private PdfData extractAllFromPdf(StatementFile file) {
        PdfData data = new PdfData();
        try {
            String filePath = "uploads/" + file.getOriginalFileName();
            String rawText = pdfService.extractText(filePath);
            if (rawText == null || rawText.isBlank()) return data;

            String textFixed = rawText.replace("\r", " ").replace("\u00A0", " ");

            // 1. ВЫТЯГИВАЕМ ШАПКУ
            Matcher mInc = Pattern.compile("mədaxil.*?cəmi.*?(\\d+[\\.,]\\d{2})", Pattern.CASE_INSENSITIVE).matcher(textFixed);
            if (mInc.find()) data.totalIncome = new BigDecimal(mInc.group(1).replace(',', '.'));

            Matcher mExp = Pattern.compile("məxaric.*?cəmi.*?(\\d+[\\.,]\\d{2})", Pattern.CASE_INSENSITIVE).matcher(textFixed);
            if (mExp.find()) data.totalExpense = new BigDecimal(mExp.group(1).replace(',', '.'));

            Matcher mOpen = Pattern.compile("əvvəlində qalıq.*?([+-]?\\d+[\\.,]\\d{2})", Pattern.CASE_INSENSITIVE).matcher(textFixed);
            if (mOpen.find()) data.openingBalance = new BigDecimal(mOpen.group(1).replace(',', '.'));

            Matcher mClose = Pattern.compile("sonunda qalıq.*?([+-]?\\d+[\\.,]\\d{2})", Pattern.CASE_INSENSITIVE).matcher(textFixed);
            if (mClose.find()) data.closingBalance = new BigDecimal(mClose.group(1).replace(',', '.'));

            // 2. СКАНИРУЕМ СЛОВО ЗА СЛОВОМ ДЛЯ СБОРА МЕСЯЧНОГО ДОХОДА
            String[] tokens = textFixed.split("[\\s\\n\\r\",]+");
            YearMonth currentMonth = null;
            Pattern dateP = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{4})$");
            Pattern amountP = Pattern.compile("^([+-])(\\d+[\\.,]\\d{2})$");

            for (String token : tokens) {
                Matcher dMatch = dateP.matcher(token);
                if (dMatch.matches()) {
                    int month = Integer.parseInt(dMatch.group(2));
                    int year = Integer.parseInt(dMatch.group(3));
                    currentMonth = YearMonth.of(year, month);
                    continue;
                }

                Matcher aMatch = amountP.matcher(token);
                if (aMatch.matches() && currentMonth != null) {
                    BigDecimal val = new BigDecimal(aMatch.group(2).replace(',', '.'));
                    if (aMatch.group(1).equals("+")) {
                        data.monthlyIncomes.put(currentMonth, data.monthlyIncomes.getOrDefault(currentMonth, BigDecimal.ZERO).add(val));
                    }
                }
            }

            System.out.println("LOG PDF DATA: Доход=" + data.totalIncome + ", Расход=" + data.totalExpense + ", Старт=" + data.openingBalance + ", Финиш=" + data.closingBalance);
            System.out.println("LOG PDF ДОХОДЫ ПО МЕСЯЦАМ: " + data.monthlyIncomes);

        } catch (Exception e) {
            System.err.println("Cannot extract PDF data: " + e.getMessage());
        }
        return data;
    }

    private static class PdfData {
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal openingBalance = BigDecimal.ZERO;
        BigDecimal closingBalance = BigDecimal.ZERO;
        Map<YearMonth, BigDecimal> monthlyIncomes = new TreeMap<>();
    }

    // -------- METRICS --------

    private int calcIncomeStability(BigDecimal[] monthlyIncome) {
        int totalMonths = monthlyIncome.length;
        if (totalMonths < 2) return 100;

        BigDecimal max = Arrays.stream(monthlyIncome).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal min = Arrays.stream(monthlyIncome).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal totalIncome = Arrays.stream(monthlyIncome).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avg = totalIncome.divide(BigDecimal.valueOf(totalMonths), CALC_SCALE, RoundingMode.HALF_UP);
        if (avg.compareTo(BigDecimal.ZERO) <= 0) return 0;

        BigDecimal variability = max.subtract(min).divide(avg, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal score = BigDecimal.valueOf(100).subtract(variability.multiply(BigDecimal.valueOf(100)));

        System.out.println("LOG: --- 1. СТАБИЛЬНОСТЬ ДОХОДА ---");
        System.out.println("LOG: Месяца из PDF: " + Arrays.toString(monthlyIncome));
        System.out.println("LOG: Балл: " + score);

        return clampInt(score.setScale(0, RoundingMode.HALF_UP).intValue(), 0, 100);
    }

    private int calcExpenseControl(BigDecimal totalIncome, BigDecimal totalExpense) {
        if (totalIncome.compareTo(BigDecimal.ZERO) <= 0) return 0;

        BigDecimal ratio = totalExpense.divide(totalIncome, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal score = BigDecimal.valueOf(100).subtract(ratio.multiply(BigDecimal.valueOf(100)));
        int finalScore = clampInt(score.setScale(0, RoundingMode.HALF_UP).intValue(), 0, 100);

        if (finalScore <= 2) return 0; // Строгое правило

        System.out.println("LOG: --- 2. РАСХОДЫ ---");
        System.out.println("LOG: PDF Income: " + totalIncome + ", PDF Expense: " + totalExpense + " | Балл: " + finalScore);

        return finalScore;
    }

    private int calcBalanceDynamicsFromPdf(BigDecimal start, BigDecimal end) {
        // Если в конце периода на счету 0 или меньше — это 0 баллов, без вариантов.
        if (end.compareTo(BigDecimal.ZERO) <= 0) {
            System.out.println("LOG: --- 3. БАЛАНС -> Слит в ноль или минус. БАЛЛ=0 ---");
            return 0;
        }

        // Если в начале был 0, а в конце появились деньги — это хороший знак (рост с нуля).
        // Даем 70 баллов за накопление, но не 100, так как нет истории.
        if (start.compareTo(BigDecimal.ZERO) <= 0) {
            return 70;
        }

        // Считаем процент изменения: (Конец - Начало) / Начало
        BigDecimal growthRate = end.subtract(start).divide(start, CALC_SCALE, RoundingMode.HALF_UP);

        // Базовая оценка — 60 баллов (если баланс не изменился).
        // Добавляем или отнимаем баллы в зависимости от роста/падения.
        BigDecimal score = BigDecimal.valueOf(60).add(growthRate.multiply(BigDecimal.valueOf(40)));

        int finalScore = clampInt(score.setScale(0, RoundingMode.HALF_UP).intValue(), 0, 100);

        System.out.println("LOG: --- 3. БАЛАНС ---");
        System.out.println("LOG: Старт: " + start + ", Конец: " + end + " | Динамика: " + finalScore);

        return finalScore;
    }
    private int calcPaymentHistory(Map<YearMonth, MonthAgg> monthMap, List<YearMonth> months) {
        int n = months.size();
        if (n == 0) return 0;

        long obligationMonths = months.stream().filter(m -> monthMap.get(m).hasObligation).count();
        double a = (double) obligationMonths / n;

        List<Integer> days = new ArrayList<>();
        months.forEach(m -> {
            if (monthMap.containsKey(m)) days.addAll(monthMap.get(m).paymentDays);
        });
        double avg = days.stream().mapToInt(i -> i).average().orElse(15.0);

        long stableMonths = months.stream()
                .filter(m -> monthMap.containsKey(m) && monthMap.get(m).paymentDays.stream().anyMatch(d -> Math.abs(d - avg) <= 3))
                .count();
        double b = (double) stableMonths / n;

        boolean hasCr = months.stream().anyMatch(m -> monthMap.containsKey(m) && monthMap.get(m).hasCredit);

        long distinctCore = months.stream()
                .filter(monthMap::containsKey)
                .flatMap(m -> monthMap.get(m).categories.stream())
                .filter(EXPENSE_CATS::contains)
                .distinct()
                .count();
        double c = hasCr ? 1.0 : Math.min(1.0, distinctCore / 3.0);

        double init = (a * 40.0) + (b * 30.0) + (c * 30.0);
        double penalty = hasCr ? 0 : 12.5;

        System.out.println("LOG: --- 4. ПЛАТЕЖИ -> Балл: " + (init - penalty) + " ---");

        return clampInt((int) Math.round(init - penalty), 0, 100);
    }

    private Map<YearMonth, MonthAgg> buildMonthlyAggregates(List<Transaction> sortedTxs) {
        Map<YearMonth, MonthAgg> map = new HashMap<>();
        for (Transaction t : sortedTxs) {
            LocalDate d = toLocalDate(t.getOperationDate());
            YearMonth ym = YearMonth.from(d);

            MonthAgg agg = map.computeIfAbsent(ym, k -> new MonthAgg());
            BigDecimal amt = t.getAmount().abs();

            if (t.getCategory() == CategoryType.INCOME || t.getCategory() == CategoryType.REGULAR_INCOME) {
                agg.income = agg.income.add(amt);
            } else {
                agg.expense = agg.expense.add(amt);
                agg.categories.add(t.getCategory());
            }

            if (OBLIGATION_CATS.contains(t.getCategory())) {
                agg.hasObligation = true;
                agg.paymentDays.add(d.getDayOfMonth());
                if (t.getCategory() == CategoryType.CREDIT) {
                    agg.hasCredit = true;
                }
            }
        }
        return map;
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static class MonthAgg {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        boolean hasObligation = false;
        boolean hasCredit = false;
        Set<Integer> paymentDays = new HashSet<>();
        Set<CategoryType> categories = new HashSet<>();
    }
}