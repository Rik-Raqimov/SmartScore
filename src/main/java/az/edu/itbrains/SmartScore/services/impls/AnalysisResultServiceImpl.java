package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisHistoryItemDto;
import az.edu.itbrains.SmartScore.dtos.analysisResult.AnalysisResultDto;
import az.edu.itbrains.SmartScore.dtos.user.UserProfileDto;
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
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
                .filter(t -> t.getOperationDate() != null && t.getAmount() != null)
                .sorted(Comparator.comparing(Transaction::getOperationDate))
                .toList();

        if (sorted.isEmpty()) return new AnalysisResultDto();

        // 1. СЧИТАЕМ ПРИХОД И РАСХОД ИЗ ТРАНЗАКЦИЙ
        BigDecimal totalIncome = sorted.stream()
                .filter(t -> t.getCategory() == CategoryType.INCOME)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpense = sorted.stream()
                .filter(t -> t.getCategory() != CategoryType.INCOME)
                .map(Transaction::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. ГРУППИРОВКА ПО МЕСЯЦАМ (БЕЗОПАСНАЯ)
        Map<YearMonth, BigDecimal> monthlyMap = sorted.stream()
                .filter(t -> t.getCategory() == CategoryType.INCOME)
                .collect(Collectors.toMap(
                        t -> YearMonth.from(toLocalDate(t.getOperationDate())),
                        Transaction::getAmount,
                        BigDecimal::add // Суммируем при дубликатах ключей
                ));

        BigDecimal[] monthlyIncomes = monthlyMap.values().toArray(new BigDecimal[0]);
        List<YearMonth> months = sorted.stream()
                .map(t -> YearMonth.from(toLocalDate(t.getOperationDate())))
                .distinct().sorted().toList();

        // 3. БАЛАНС (С ПРОВЕРКОЙ НА NULL)
        BigDecimal openingBalance = extractStartBalance(lastFile);
        if (openingBalance.compareTo(BigDecimal.ZERO) == 0) openingBalance = BigDecimal.ONE;

        BigDecimal closingBalance = openingBalance.add(totalIncome).subtract(totalExpense);

        // 4. СЧИТАЕМ МЕТРИКИ
        int incomeScore = calcIncomeStability(monthlyIncomes);
        int expenseScore = calcExpenseControl(totalIncome, totalExpense);
        int balanceScore = calcBalanceDynamicsFromPdf(openingBalance, closingBalance);

        Map<YearMonth, MonthAgg> monthAggs = buildMonthlyAggregates(sorted);
        int paymentScore = calcPaymentHistory(monthAggs, months);

        BigDecimal finalScoreRaw =
                BigDecimal.valueOf(incomeScore).multiply(BigDecimal.valueOf(0.25))
                        .add(BigDecimal.valueOf(expenseScore).multiply(BigDecimal.valueOf(0.20)))
                        .add(BigDecimal.valueOf(balanceScore).multiply(BigDecimal.valueOf(0.25)))
                        .add(BigDecimal.valueOf(paymentScore).multiply(BigDecimal.valueOf(0.30)));

        int finalScore = clampInt(finalScoreRaw.setScale(0, RoundingMode.HALF_UP).intValue(), 0, 100);

        System.out.println("LOG: Final Analysis -> Score: " + finalScore + " | Months: " + months.size());

        AnalysisResult entity = new AnalysisResult();
        entity.setUser(user);
        entity.setIncomeStability(incomeScore);
        entity.setExpenseControl(expenseScore);
        entity.setBalanceDynamics(balanceScore);
        entity.setPaymentHistory(paymentScore);
        entity.setScore(finalScore);
        entity.setCalculatedAt(new Date());
        entity.setPeriodMonths(Math.max(1, months.size()));
        analysisResultRepository.saveAndFlush(entity);

        return modelMapper.map(entity, AnalysisResultDto.class);
    }

    private BigDecimal extractStartBalance(StatementFile file) {
        try {
            String path = file.getStoredFilePath();
            String rawText = pdfService.extractText(path);
            if (rawText == null) return BigDecimal.ZERO;

            String flatText = rawText.replace("\n", " ").replace("\r", " ").replaceAll("\\s+", " ");
            Matcher m = Pattern.compile("(?:Giriş qalıq|əvvəlində qalıq|opening balance).*?([+-]?\\d+[\\.,]\\d{2})",
                    Pattern.CASE_INSENSITIVE).matcher(flatText);

            if (m.find()) {
                return new BigDecimal(m.group(1).replace(',', '.'));
            }
        } catch (Exception ignored) {}
        return BigDecimal.ZERO;
    }

    private int calcIncomeStability(BigDecimal[] monthlyIncome) {
        if (monthlyIncome.length < 1) return 10;
        if (monthlyIncome.length < 2) return 100;
        BigDecimal total = Arrays.stream(monthlyIncome).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = total.divide(BigDecimal.valueOf(monthlyIncome.length), CALC_SCALE, RoundingMode.HALF_UP);
        long stable = Arrays.stream(monthlyIncome).filter(m -> m.compareTo(avg.multiply(BigDecimal.valueOf(0.6))) >= 0).count();
        return (int) ((double) stable / monthlyIncome.length * 100);
    }

    private int calcExpenseControl(BigDecimal totalIncome, BigDecimal totalExpense) {
        if (totalIncome.compareTo(BigDecimal.ZERO) <= 0) return 10;
        BigDecimal savingsRatio = totalIncome.subtract(totalExpense).divide(totalIncome, CALC_SCALE, RoundingMode.HALF_UP);
        double r = savingsRatio.doubleValue();
        if (r >= 0.25) return 100;
        if (r >= 0.05) return 60 + (int)(r * 150);
        return Math.max(15, 40 + (int)(r * 100));
    }

    private int calcBalanceDynamicsFromPdf(BigDecimal start, BigDecimal end) {
        if (end.compareTo(BigDecimal.ZERO) <= 0) return 10;
        if (start.compareTo(BigDecimal.ZERO) <= 0 || start.compareTo(BigDecimal.ONE) == 0) return 70;
        double r = end.divide(start, CALC_SCALE, RoundingMode.HALF_UP).doubleValue();
        if (r >= 1.1) return 100;
        if (r >= 1.0) return 80;
        return 40;
    }

    private int calcPaymentHistory(Map<YearMonth, MonthAgg> monthMap, List<YearMonth> months) {
        if (months.isEmpty()) return 0;
        long obligationMonths = months.stream()
                .filter(m -> monthMap.get(m) != null && monthMap.get(m).hasObligation)
                .count();
        double a = (double) obligationMonths / months.size();
        boolean hasCredit = months.stream()
                .filter(m -> monthMap.get(m) != null)
                .anyMatch(m -> monthMap.get(m).hasCredit);
        return clampInt((int) (a * 60 + (hasCredit ? 40 : 20)), 0, 100);
    }

    @Override
    @Transactional
    public AnalysisResultDto processAndAnalyze(MultipartFile file) {
        try {
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            java.io.File uploadDir = new java.io.File("uploads");
            if (!uploadDir.exists()) uploadDir.mkdirs();
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

            String rawText = pdfService.extractText(destination.getAbsolutePath());
            List<Transaction> transactions = gptService.analyzeStatementAndGetTransactions(rawText);

            for (Transaction tx : transactions) {
                tx.setStatementFile(statementFile);
                tx.setUser(currentUser);
            }
            transactionRepository.saveAll(transactions);
            transactionRepository.flush();

            return calculateScore(currentUser);
        } catch (Exception e) { throw new RuntimeException(e.getMessage()); }
    }

    @Override
    public AnalysisResultDto getLatestResultForUser() {
        return analysisResultRepository.findTopByUserIdOrderByCalculatedAtDesc(userService.getCurrentUser().getId())
                .map(entity -> modelMapper.map(entity, AnalysisResultDto.class)).orElse(new AnalysisResultDto());
    }

    @Override
    public UserProfileDto getUserProfileData() {
        User user = userService.getCurrentUser();
        List<AnalysisResult> results = user.getAnalysisResults();
        UserProfileDto profileDto = new UserProfileDto();
        profileDto.setTotalAnalyses(results.size());
        if (!results.isEmpty()) {
            AnalysisResult latest = results.get(0);
            profileDto.setLastResult(latest.getScore() + "%");
            profileDto.setLastAnalysisDate(formatDate(latest.getCalculatedAt()));
        }
        profileDto.setHistory(results.stream().map(result -> {
            AnalysisHistoryItemDto item = new AnalysisHistoryItemDto();
            item.setDate(formatDate(result.getCalculatedAt()));
            item.setTime(formatTime(result.getCalculatedAt()));
            item.setScore(result.getScore());
            item.setStatus(result.getScore() >= 69 ? "Yüksək" : (result.getScore() >= 39 ? "Normal" : "Pis"));
            return item;
        }).toList());
        return profileDto;
    }

    private Map<YearMonth, MonthAgg> buildMonthlyAggregates(List<Transaction> sortedTxs) {
        Map<YearMonth, MonthAgg> map = new HashMap<>();
        for (Transaction t : sortedTxs) {
            YearMonth ym = YearMonth.from(toLocalDate(t.getOperationDate()));
            MonthAgg agg = map.computeIfAbsent(ym, k -> new MonthAgg());
            if (t.getCategory() == CategoryType.INCOME) agg.income = agg.income.add(t.getAmount());
            else agg.expense = agg.expense.add(t.getAmount().abs());
            if (OBLIGATION_CATS.contains(t.getCategory())) {
                agg.hasObligation = true;
                if (t.getCategory() == CategoryType.CREDIT) agg.hasCredit = true;
            }
        }
        return map;
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? LocalDate.now() : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
    private int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private String formatDate(Date date) { return date == null ? "" : new SimpleDateFormat("dd MMMM yyyy", new Locale("az")).format(date); }
    private String formatTime(Date date) { return date == null ? "" : new SimpleDateFormat("HH:mm").format(date); }

    private static class MonthAgg {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        boolean hasObligation = false;
        boolean hasCredit = false;
    }
}