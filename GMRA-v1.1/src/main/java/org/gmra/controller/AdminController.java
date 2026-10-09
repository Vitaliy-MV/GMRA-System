package org.gmra.controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gmra.dao.SystemDao;
import org.gmra.dto.PredictionResult;
import org.gmra.integration.UsgsWaterClient;
import org.gmra.service.ForecastOrchestratorService;
import org.gmra.service.ForecastVectorCalculator;
import org.gmra.service.MlPredictionService;
import org.gmra.service.PredictionCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AdminController {

	@Autowired
    private MlPredictionService mlService;
    @Autowired
    private SystemDao systemDao;
    
    @Autowired
    private UsgsWaterClient USGS;
    
    @Autowired
    private ForecastVectorCalculator forecastVector;
    @Autowired
    private ForecastOrchestratorService forecastOrchestratorService;
    
    @Autowired
    private PredictionCalculator prediction;
    
    /*
	@GetMapping("/api/admin/load-water-data")
    @ResponseBody
    public ResponseEntity<String> trainMlModel(@RequestParam(required = false) String date) {
        try {
        	USGS.downloadHistoricalWaterData();
            return ResponseEntity.ok("Water data by USGS downloaded.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("USGS error: " + e.getMessage());
        }
        
    }
    */
    
    /*
    // Test FP, TP, with Trigger Analysis
    @GetMapping("/api/admin/optimize/testTriggers")
    @ResponseBody
    public ResponseEntity<String> testTriggers() {
        try {
            List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
            int tp = 0, fp = 0, fn = 0, tn = 0;
            
            // Корзины для анализа FP (где прячутся ложные тревоги)
            int fp_90_100 = 0, tp_90_100 = 0;
            int fp_85_89  = 0, tp_85_89 = 0;
            int fp_80_84  = 0, tp_80_84 = 0;
            int fp_75_79  = 0, tp_75_79 = 0;

            // Карта для подсчета частоты триггеров в событиях FP
            Map<String, Integer> fpTriggerCounts = new HashMap<>();

            System.out.println("🚀 Старт бэктеста (API Simulation) [Анализ триггеров FP]...");
            long startTime = System.currentTimeMillis();

            for (int i = 14; i < allData.size() - 1; i++) {
                LocalDate baseDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                Map<String, PredictionResult> forecasts = forecastOrchestratorService.generateFullForecast(baseDate, allData);
                
                PredictionResult todayForecast = forecasts.get("today");
                if (todayForecast == null) continue;

                double prob = todayForecast.probabilityPercent;
                boolean isFinalAlarm = prob >= 75.0; 

                Object mObj = allData.get(i + 1).get("max_magnitude");
                double actualMag = (mObj != null) ? ((Number) mObj).doubleValue() : 0.0;
                boolean isEq = actualMag >= 6.0;

                if (isFinalAlarm && isEq) {
                    tp++;
                    if (prob >= 90.0) tp_90_100++;
                    else if (prob >= 85.0) tp_85_89++;
                    else if (prob >= 80.0) tp_80_84++;
                    else tp_75_79++;
                }
                else if (isFinalAlarm && !isEq) {
                    fp++; // Ложная тревога. 
                    if (prob >= 90.0) fp_90_100++;
                    else if (prob >= 85.0) fp_85_89++;
                    else if (prob >= 80.0) fp_80_84++;
                    else fp_75_79++;

                    // СБОР ТРИГГЕРОВ: Записываем, кто виноват в ложной тревоге
                    if (todayForecast.activeTriggers != null) {
                        for (String trigger : todayForecast.activeTriggers) {
                            fpTriggerCounts.put(trigger, fpTriggerCounts.getOrDefault(trigger, 0) + 1);
                        }
                    }
                }
                else if (!isFinalAlarm && isEq) {
                    fn++; 
                }
                else if (!isFinalAlarm && !isEq) {
                    tn++;
                }
            }

            long endTime = System.currentTimeMillis();
            long durationSec = (endTime - startTime) / 1000;

            double precision = (tp + fp == 0) ? 0 : (double) tp / (tp + fp) * 100.0;
            double recall = (tp + fn == 0) ? 0 : (double) tp / (tp + fn) * 100.0;
            double f05 = (precision + recall == 0) ? 0 : (1.25 * precision * recall) / ((0.25 * precision) + recall);

            // Сортируем триггеры по частоте спама
            StringBuilder triggerReport = new StringBuilder();
            fpTriggerCounts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(20) // Показываем Топ-20 самых частых триггеров
                .forEach(e -> triggerReport.append(String.format("  - %s: %d раз\n", e.getKey(), e.getValue())));

            String report = String.format(
                "=== 🏆 НАГРУЗОЧНЫЙ ТЕСТ ОРКЕСТРАТОРА (Анализ FP) ===\n" +
                "TP: %d | FP: %d | FN: %d | TN: %d\n" +
                "Precision: %.2f%% | Recall: %.2f%% | F0.5: %.2f%%\n" +
                "Время выполнения: %d сек.\n" +
                "----------------------------------------------------\n" +
                "ГДЕ ПРЯЧУТСЯ ЛОЖНЫЕ ТРЕВОГИ (FP = %d):\n" +
                "Диапазон [90%% - 100%%] (Экстремальная уверенность): %d событий\n" +
                "Диапазон [85%% - 89%%] (Высокая уверенность): %d событий\n" +
                "Диапазон [80%% - 84%%] (Средняя уверенность): %d событий\n" +
                "Диапазон [75%% - 79%%] (На грани порога): %d событий\n" +
                "----------------------------------------------------\n" +
                "Реальные ТРЕВОГИ (TP = %d):\n" +
                "Диапазон [90%% - 100%%] (Экстремальная уверенность): %d событий\n" +
                "Диапазон [85%% - 89%%] (Высокая уверенность): %d событий\n" +
                "Диапазон [80%% - 84%%] (Средняя уверенность): %d событий\n" +
                "Диапазон [75%% - 79%%] (На грани порога): %d событий\n" +
                "----------------------------------------------------\n" +
                "🔥 ТОП ТРИГГЕРОВ, ГЕНЕРИРУЮЩИХ FP:\n%s\n" +
                "==========================================================",
                tp, fp, fn, tn, precision, recall, f05, durationSec,
                fp, fp_90_100, fp_85_89, fp_80_84, fp_75_79,
                tp, tp_90_100, tp_85_89, tp_80_84, tp_75_79,
                triggerReport.toString()
            );

            System.out.println(report);
            return ResponseEntity.ok("<pre>" + report + "</pre>");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }
    */
    
    /*
    // Test FP, TP
    @GetMapping("/api/admin/optimize/testFP")
    @ResponseBody
    public ResponseEntity<String> testOrchestratorFP() {
        try {
            List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
            int tp = 0, fp = 0, fn = 0, tn = 0;
            
            // Корзины для анализа FP (где прячутся ложные тревоги)
            int fp_90_100 = 0, tp_90_100 = 0;
            int fp_85_89  = 0, tp_85_89 = 0;
            int fp_80_84  = 0, tp_80_84 = 0;
            int fp_75_79  = 0, tp_75_79 = 0;

            System.out.println("🚀 Старт бэктеста (API Simulation) [Анализ диапазона FP]...");
            long startTime = System.currentTimeMillis();

            for (int i = 14; i < allData.size() - 1; i++) {
                LocalDate baseDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                Map<String, PredictionResult> forecasts = forecastOrchestratorService.generateFullForecast(baseDate, allData);
                
                PredictionResult todayForecast = forecasts.get("today");
                if (todayForecast == null) continue;

                double prob = todayForecast.probabilityPercent;
                boolean isFinalAlarm = prob >= 75.0; 

                Object mObj = allData.get(i + 1).get("max_magnitude");
                double actualMag = (mObj != null) ? ((Number) mObj).doubleValue() : 0.0;
                boolean isEq = actualMag >= 6.0;

                if (isFinalAlarm && isEq) {
                    tp++;
                    if (prob >= 90.0) tp_90_100++;
                    else if (prob >= 85.0) tp_85_89++;
                    else if (prob >= 80.0) tp_80_84++;
                    else tp_75_79++;
                }
                else if (isFinalAlarm && !isEq) {
                    fp++; // Ложная тревога. Проверяем, насколько система была уверена:
                    if (prob >= 90.0) fp_90_100++;
                    else if (prob >= 85.0) fp_85_89++;
                    else if (prob >= 80.0) fp_80_84++;
                    else fp_75_79++;
                }
                else if (!isFinalAlarm && isEq) {
                    fn++; 
                }
                else if (!isFinalAlarm && !isEq) {
                    tn++;
                }
            }

            long endTime = System.currentTimeMillis();
            long durationSec = (endTime - startTime) / 1000;

            double precision = (tp + fp == 0) ? 0 : (double) tp / (tp + fp) * 100.0;
            double recall = (tp + fn == 0) ? 0 : (double) tp / (tp + fn) * 100.0;
            double f05 = (precision + recall == 0) ? 0 : (1.25 * precision * recall) / ((0.25 * precision) + recall);

            String report = String.format(
                "=== 🏆 НАГРУЗОЧНЫЙ ТЕСТ ОРКЕСТРАТОРА (Анализ FP) ===\n" +
                "TP: %d | FP: %d | FN: %d | TN: %d\n" +
                "Precision: %.2f%% | Recall: %.2f%% | F0.5: %.2f%%\n" +
                "Время выполнения: %d сек.\n" +
                "----------------------------------------------------\n" +
                "ГДЕ ПРЯЧУТСЯ ЛОЖНЫЕ ТРЕВОГИ (FP = %d):\n" +
                "Диапазон [90%% - 100%%] (Экстремальная уверенность): %d событий\n" +
                "Диапазон [85%% - 89%%] (Высокая уверенность): %d событий\n" +
                "Диапазон [80%% - 84%%] (Средняя уверенность): %d событий\n" +
                "Диапазон [75%% - 79%%] (На грани порога): %d событий\n" +
                "----------------------------------------------------\n" +
                "Реальные ТРЕВОГИ (TP = %d):\n" +
                "Диапазон [90%% - 100%%] (Экстремальная уверенность): %d событий\n" +
                "Диапазон [85%% - 89%%] (Высокая уверенность): %d событий\n" +
                "Диапазон [80%% - 84%%] (Средняя уверенность): %d событий\n" +
                "Диапазон [75%% - 79%%] (На грани порога): %d событий\n" +
                "==========================================================",
                tp, fp, fn, tn, precision, recall, f05, durationSec,
                fp, fp_90_100, fp_85_89, fp_80_84, fp_75_79,
                tp, tp_90_100, tp_85_89, tp_80_84, tp_75_79
            );

            System.out.println(report);
            return ResponseEntity.ok("<pre>" + report + "</pre>");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }
    */    
    
    /*
    // Test FP, FN, TP, TN
    @GetMapping("/api/admin/optimize/test")
    @ResponseBody
    public ResponseEntity<String> testOrchestrator() {
        try {
            List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
            int tp = 0, fp = 0, fn = 0, tn = 0;
            
            // Корзины для анализа FN (где прячутся пропущенные землетрясения)
            int fn_65_74 = 0;
            int fn_55_64 = 0;
            int fn_40_54 = 0;
            int fn_below_40 = 0;

            System.out.println("🚀 Старт бэктеста (API Simulation) [Анализ диапазона FN]...");
            long startTime = System.currentTimeMillis();

            for (int i = 14; i < allData.size() - 1; i++) {
                LocalDate baseDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                Map<String, PredictionResult> forecasts = forecastOrchestratorService.generateFullForecast(baseDate, allData);
                
                PredictionResult todayForecast = forecasts.get("today");
                if (todayForecast == null) continue;

                double prob = todayForecast.probabilityPercent;
                boolean isFinalAlarm = prob >= 75.0; 

                Object mObj = allData.get(i + 1).get("max_magnitude");
                double actualMag = (mObj != null) ? ((Number) mObj).doubleValue() : 0.0;
                boolean isEq = actualMag >= 6.0;

                if (isFinalAlarm && isEq) tp++;
                else if (isFinalAlarm && !isEq) fp++;
                else if (!isFinalAlarm && isEq) {
                    fn++; // Пропустили землетрясение. Проверяем, какая была вероятность:
                    if (prob >= 65.0 && prob < 75.0) fn_65_74++;
                    else if (prob >= 55.0 && prob < 65.0) fn_55_64++;
                    else if (prob >= 40.0 && prob < 55.0) fn_40_54++;
                    else fn_below_40++;
                }
                else if (!isFinalAlarm && !isEq) tn++;
            }

            long endTime = System.currentTimeMillis();
            long durationSec = (endTime - startTime) / 1000;

            double precision = (tp + fp == 0) ? 0 : (double) tp / (tp + fp) * 100.0;
            double recall = (tp + fn == 0) ? 0 : (double) tp / (tp + fn) * 100.0;
            double f05 = (precision + recall == 0) ? 0 : (1.25 * precision * recall) / ((0.25 * precision) + recall);

            String report = String.format(
                "=== 🏆 НАГРУЗОЧНЫЙ ТЕСТ ОРКЕСТРАТОРА (Анализ FN) ===\n" +
                "TP: %d | FP: %d | FN: %d | TN: %d\n" +
                "Precision: %.2f%% | Recall: %.2f%% | F0.5: %.2f%%\n" +
                "----------------------------------------------------\n" +
                "ГДЕ ПРЯЧУТСЯ ПРОПУЩЕННЫЕ ЗЕМЛЕТРЯСЕНИЯ (FN = %d):\n" +
                "Диапазон [65%% - 74%%] (Оранжевая зона, верх): %d событий\n" +
                "Диапазон [55%% - 64%%] (Оранжевая зона, низ): %d событий\n" +
                "Диапазон [40%% - 54%%] (Желтая зона): %d событий\n" +
                "Диапазон [< 40%%] (Зеленая зона): %d событий\n" +
                "==========================================================",
                tp, fp, fn, tn, precision, recall, f05, 
                fn, fn_65_74, fn_55_64, fn_40_54, fn_below_40
            );

            System.out.println(report);
            return ResponseEntity.ok("<pre>" + report + "</pre>");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }
    */
    
    /*
    // Test RED ZONE (All system)
    @GetMapping("/api/admin/optimize/stress-test")
    @ResponseBody
    public ResponseEntity<String> testOrchestratorLoadTest() {
        try {
        	List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
            int tp = 0, fp = 0, fn = 0, tn = 0;

            System.out.println("🚀 Старт бэктеста (API Simulation) [На Сегодня (24ч)]...");
            
            // Засекаем время для оценки пропускной способности сервера
            long startTime = System.currentTimeMillis();

            for (int i = 14; i < allData.size() - 1; i++) {
                LocalDate baseDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();

                // Имитация вызова контроллера: обращаемся напрямую к оркестратору,
                // избавляясь от дублирования логики в теле теста[cite: 7].
                Map<String, PredictionResult> forecasts = forecastOrchestratorService.generateFullForecast(baseDate, allData);
                
                PredictionResult todayForecast = forecasts.get("today");
                if (todayForecast == null) continue;

                // Проверяем только красную зону (≥ 75%)[cite: 7]
                boolean isFinalAlarm = todayForecast.probabilityPercent >= 75.0; 

                // Фактический результат на следующий шаг для проверки прогноза "На сегодня"[cite: 7]
                Object mObj = allData.get(i + 1).get("max_magnitude");
                double actualMag = (mObj != null) ? ((Number) mObj).doubleValue() : 0.0;
                boolean isEq = actualMag >= 6.5;

                // Подсчет метрик[cite: 7]
                if (isFinalAlarm && isEq) tp++;
                else if (isFinalAlarm && !isEq) fp++;
                else if (!isFinalAlarm && isEq) fn++;
                else if (!isFinalAlarm && !isEq) tn++;
            }

            long endTime = System.currentTimeMillis();
            long durationSec = (endTime - startTime) / 1000;
            long reqPerSec = (durationSec > 0) ? (allData.size() / durationSec) : allData.size();

            // Вычисляем итоговые показатели[cite: 7]
            double precision = (tp + fp == 0) ? 0 : (double) tp / (tp + fp) * 100.0;
            double recall = (tp + fn == 0) ? 0 : (double) tp / (tp + fn) * 100.0;
            double f05 = (precision + recall == 0) ? 0 : (1.25 * precision * recall) / ((0.25 * precision) + recall);

            String report = String.format(
                "=== 🏆 НАГРУЗОЧНЫЙ ТЕСТ ОРКЕСТРАТОРА (API Simulation) ===\n" +
                "Обработано индексов: %d\n" +
                "Время выполнения: %d секунд\n" +
                "Скорость обработки: ~%d прогнозов в секунду\n" +
                "TP: %d | FP: %d | FN: %d | TN: %d\n" +
                "Precision: %.2f%% | Recall: %.2f%% | F0.5: %.2f%%\n" +
                "==========================================================",
                allData.size(), durationSec, reqPerSec, tp, fp, fn, tn, precision, recall, f05
            );

            System.out.println(report);
            return ResponseEntity.ok("<pre>" + report + "</pre>");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка тестирования: " + e.getMessage());
        }
    }
	*/
    

    /*
    // TEST for 3ML
	@GetMapping("/api/admin/optimize")
    @ResponseBody
    public ResponseEntity<String> trainMlModel(@RequestParam(required = false) String date) {
        try {
            mlService.runMlBacktest();
            return ResponseEntity.ok("The model was trained successfully.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Training error: " + e.getMessage());
        }
        
    }
   */
  
}
