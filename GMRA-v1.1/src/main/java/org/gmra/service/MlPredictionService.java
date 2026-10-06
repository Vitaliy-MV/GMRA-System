package org.gmra.service;

import org.gmra.dao.SystemDao;
import org.springframework.stereotype.Service;

import weka.classifiers.CostMatrix;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class MlPredictionService {

    public static class MlForecast {
        public double probability;
        public boolean isAlarm;
        public List<String> triggers;
        
        public MlForecast(double probability, boolean isAlarm, List<String> triggers) {
            this.probability = Math.round(probability * 100.0) / 100.0;
            this.isAlarm = isAlarm;
            this.triggers = triggers != null ? triggers : new ArrayList<>();
        }
    }

    private CostSensitiveClassifier trainedModel72h;
    private CostSensitiveClassifier trainedModel48h;
    private CostSensitiveClassifier trainedModel24h;
    
    private Instances emptyDatasetStructure;
    
    private final PredictionCalculator calculator;
    private final SystemDao systemDao;
    private final Tools tools;

    public MlPredictionService(PredictionCalculator calculator, SystemDao systemDao, Tools tools) {
        this.tools = tools;
        this.systemDao = systemDao;
        this.calculator = calculator;
        this.emptyDatasetStructure = buildDatasetStructure();
    }

    // =================================
    // ENSEMBLE TRAINING (For Cron Run)
    // =================================

    public void trainAllModels() throws Exception {
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        tools.writeLog("⏳ Start model training (3 Days, 2 Days, 24h)...");
        // Штраф FN = 30.0 зафиксирован для всех горизонтов согласно тестам
        trainedModel72h = trainModelForWindow(allData, 1, 3, 6.0, 30.0);
        trainedModel48h = trainModelForWindow(allData, 1, 2, 6.0, 40.0);
        trainedModel24h = trainModelForWindow(allData, 1, 1, 6.0, 50.0);
        tools.writeLog("Model training successful.");
    }

    private CostSensitiveClassifier trainModelForWindow(List<Map<String, Object>> allData, int targetStart, int targetEnd, double magnitude, double penalty) throws Exception {
        Instances trainDataset = new Instances(emptyDatasetStructure, 0);
        int maxIndex = allData.size() - targetEnd-1;

        for (int i = 14; i < maxIndex; i++) {
            double[] features = calculator.extractFeatures(allData, i);
            if (features == null) continue;

            double maxFutureMag = 0.0;
            int countM5 = 0;

            for (int f = targetStart; f <= targetEnd; f++) {
                Object mObj = allData.get(i + f).get("max_magnitude");
                if (mObj != null) {
                    double mag = ((Number) mObj).doubleValue();
                    maxFutureMag = Math.max(maxFutureMag, mag);
                    if (mag >= 5.3) {
                        countM5++;
                    }
                }
            }
            
            // Soft Target: Удар >= 6.0 ИЛИ рой из двух событий >= 5.3
            boolean isEarthquake = (maxFutureMag >= magnitude) || (maxFutureMag >= 5.5 && countM5 >= 3);
            String targetValue = isEarthquake ? "Earthquake" : "Safe";
            
            DenseInstance instance = new DenseInstance(14);
            instance.setDataset(trainDataset); 
            
            for (int j = 0; j < features.length; j++) {
                instance.setValue(j, features[j]);
            }
            instance.setValue(13, targetValue); 
            
            trainDataset.add(instance);
        }

        RandomForest rf = new RandomForest();
        rf.setNumIterations(200); 

        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0); 
        costMatrix.setCell(1, 1, 0.0); 
        costMatrix.setCell(0, 1, 1.0); // FP Penalty
        costMatrix.setCell(1, 0, penalty); // FN Penalty (30.0)

        CostSensitiveClassifier csc = new CostSensitiveClassifier();
        csc.setClassifier(rf);
        csc.setCostMatrix(costMatrix);
        csc.setMinimizeExpectedCost(false); 
        csc.buildClassifier(trainDataset);
        
        return csc;
    }

    // =======================================
    // INTERFACES FOR PREDICTION CALCULATOR
    // ========================================

    public MlForecast getPredictionFor72h(List<Map<String, Object>> allData, int baseIndex) throws Exception {
        return processPrediction(allData, trainedModel72h, 87.0, "3 Days", baseIndex);
    }

    public MlForecast getPredictionFor48h(List<Map<String, Object>> allData, int baseIndex) throws Exception {
        return processPrediction(allData, trainedModel48h, 87.0, "2 Days", baseIndex);
    }

    public MlForecast getPredictionFor24h(List<Map<String, Object>> allData, int baseIndex) throws Exception {
        return processPrediction(allData, trainedModel24h, 73.0, "1 Day", baseIndex);
    }
    
    private MlForecast processPrediction(List<Map<String, Object>> allData, CostSensitiveClassifier model, double threshold, String horizonName, int baseIndex) throws Exception {
        if (model == null) return new MlForecast(0.0, false, new ArrayList<>());

        double[] features = calculator.extractFeatures(allData, baseIndex);
        if (features == null) return new MlForecast(0.0, false, new ArrayList<>());

        double prob = predictWithModel(features, model);
        boolean isAlarm = prob >= threshold;
        
        List<String> triggers = generateMlTriggers(features, isAlarm, horizonName);
        return new MlForecast(prob, isAlarm, triggers);
    }

    private double predictWithModel(double[] features, CostSensitiveClassifier model) throws Exception {
        DenseInstance instance = new DenseInstance(14);
        instance.setDataset(emptyDatasetStructure);
        for (int j = 0; j < features.length; j++) {
            instance.setValue(j, features[j]);
        }
        instance.setMissing(13);
        double[] probabilities = model.distributionForInstance(instance);
        return probabilities[1] * 100.0;
    }

    // ==========================
    // EXPLAINABILITY LAYER (XAI)
    // ==========================

    private List<String> generateMlTriggers(double[] features, boolean isAlarm, String horizon) {
        List<String> triggers = new ArrayList<>();
        if (!isAlarm)
            return triggers;
        double currentBVal = features[6];
        double pearson = features[7];
        double etasRatio = features[2];
        double waterTempDelta = features[10];

        triggers.add(String.format(java.util.Locale.US, "🤖 b-value (%.2f)", currentBVal));
        triggers.add(String.format(java.util.Locale.US, "🤖 Pearson: Mag vs Depth (%.2f)", pearson));
        triggers.add(String.format(java.util.Locale.US, "🤖 ETAS Swarm (%.2f)", etasRatio));
        triggers.add(String.format(java.util.Locale.US, "🤖 Firehole River Δ °C (%.2f)", waterTempDelta));

        return triggers;
    }

    private Instances buildDatasetStructure() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("CurrentBenioff"));
        attributes.add(new Attribute("BenioffAccel"));
        attributes.add(new Attribute("EtasRatio"));
        attributes.add(new Attribute("CurrenDepth"));
        attributes.add(new Attribute("DepthDelta"));
        attributes.add(new Attribute("DepthAccel"));
        attributes.add(new Attribute("CurrentBValue"));
        attributes.add(new Attribute("CurrentPearson"));
        attributes.add(new Attribute("PearsonTrend"));
        attributes.add(new Attribute("MoonSpeedEx"));
        attributes.add(new Attribute("WaterTempDelta"));
        attributes.add(new Attribute("WaterTempAccel"));
        attributes.add(new Attribute("WaterTempTrend"));
        attributes.add(new Attribute("Target", Arrays.asList("Safe", "Earthquake")));

        Instances dataset = new Instances("SeismicDataset", attributes, 0);
        dataset.setClassIndex(13);
        return dataset;
    }

    // =========================================================
    // BACKTESTING OF A MODEL ENSEMBLE (80% Training / 20% Test)
    // =========================================================

    public void runMlBacktest() throws Exception {
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        System.out.println("=====================================================");
        System.out.println("🚀 СТАРТ ML БЭКТЕСТИНГА (Soft Target: M6.0+ или Рой М5.9+)");
        System.out.println("=====================================================");

        runBacktestForHorizon("1 дней", 1, 1, allData, 50.0);
        runBacktestForHorizon("48 часа", 1, 2, allData, 40.0);
        runBacktestForHorizon("72 часов", 1, 3, allData, 30.0);
    }

    private void runBacktestForHorizon(String modelName, int targetStart, int targetEnd, List<Map<String, Object>> allData, double penalty) throws Exception {
        System.out.println("\n🔄 Запуск бэктеста для модели: [" + modelName + "] (Random Forest + CostSensitive)");
        
        Instances dataset = new Instances(emptyDatasetStructure, 0);
        int splitIndex = (int) (allData.size() * 0.8);
        
        Instances trainDataset = new Instances(dataset, 0);
        int safeCount = 0, eqCount = 0;

        // 1. ОБУЧАЮЩАЯ ВЫБОРКА
        for (int i = 14; i < splitIndex; i++) {
            double[] features = calculator.extractFeatures(allData, i);
            if (features == null) continue;

            double maxFutureMag = 0.0;
            int countM5 = 0;

            for (int f = targetStart; f <= targetEnd; f++) {
                Object mObj = allData.get(i + f).get("max_magnitude");
                if (mObj != null) {
                    double mag = ((Number) mObj).doubleValue();
                    maxFutureMag = Math.max(maxFutureMag, mag);
                    if (mag >= 5.3) {
                        countM5++;
                    }
                }
            }
            
            boolean isEarthquake = (maxFutureMag >= 6.0) || (maxFutureMag >= 5.5 && countM5 >= 3);
            if (isEarthquake) eqCount++;
            else safeCount++;

            String targetValue = isEarthquake ? "Earthquake" : "Safe";
            DenseInstance instance = new DenseInstance(14);
            instance.setDataset(trainDataset); 
            
            for (int j = 0; j < features.length; j++) {
                instance.setValue(j, features[j]);
            }
            instance.setValue(13, targetValue);
            trainDataset.add(instance);
        }
       
        System.out.println(String.format("⚖️ Баланс (Soft Target): Угроз = %d, Безопасно = %d", eqCount, safeCount));
        System.out.println("⏳ Обучение Случайного Леса (Random Forest, 200 итераций) со штрафом FN = " + penalty + "...");
        
        RandomForest rf = new RandomForest();
        rf.setNumIterations(200);

        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0); 
        costMatrix.setCell(1, 1, 0.0); 
        costMatrix.setCell(0, 1, 1.0); 
        costMatrix.setCell(1, 0, penalty); 

        CostSensitiveClassifier csc = new CostSensitiveClassifier();
        csc.setClassifier(rf);
        csc.setCostMatrix(costMatrix);
        csc.setMinimizeExpectedCost(false); 
        csc.buildClassifier(trainDataset);

        // 2. ТЕСТОВАЯ ВЫБОРКА
        System.out.println("🔍 Сбор прогнозов на тестовой выборке...");
        List<double[]> testResults = new ArrayList<>();
        int maxTestIndex = allData.size() - targetEnd-1;

        for (int i = splitIndex; i <= maxTestIndex; i++) {
            double[] features = calculator.extractFeatures(allData, i);
            if (features == null) continue;

            DenseInstance instance = new DenseInstance(14);
            instance.setDataset(trainDataset);
            for (int j = 0; j < features.length; j++) {
                instance.setValue(j, features[j]);
            }
            instance.setMissing(13); 

            double[] probabilities = csc.distributionForInstance(instance);
            double dangerProbability = probabilities[1] * 100.0;

            double maxFutureMag = 0.0;
            int countM5 = 0;
            for (int f = targetStart; f <= targetEnd; f++) {
                Object mObj = allData.get(i + f).get("max_magnitude");
                if (mObj != null) {
                    double mag = ((Number) mObj).doubleValue();
                    maxFutureMag = Math.max(maxFutureMag, mag);
                    if (mag >= 5.3) {
                        countM5++;
                    }
                }
            }
            double isEarthquake = (maxFutureMag >= 6.0 || (maxFutureMag >= 5.5 && countM5 >= 3)) ? 1.0 : 0.0;
            
            testResults.add(new double[]{dangerProbability, isEarthquake});
        }

        // 3. ПОИСК ИДЕАЛЬНОГО ПОРОГА
        double bestF05 = 0;
        double bestThreshold = 0;
        String bestReport = "";

        for (double thresh = 10.0; thresh <= 100.0; thresh += 1.0) {
            int tp = 0, fp = 0, fn = 0, tn = 0;
            
            for (double[] res : testResults) {
                boolean isAlarm = res[0] >= thresh;
                boolean isEq = res[1] == 1.0;
                
                if (isAlarm && isEq) tp++;
                else if (isAlarm && !isEq) fp++;
                else if (!isAlarm && isEq) fn++;
                else if (!isAlarm && !isEq) tn++;
            }
            
            double precision = (tp + fp == 0) ? 0 : (double) tp / (tp + fp) * 100.0;
            double recall = (tp + fn == 0) ? 0 : (double) tp / (tp + fn) * 100.0;
            double f05 = (precision + recall == 0) ? 0 : (1.25 * precision * recall) / ((0.25 * precision) + recall);
            
            if (f05 > bestF05) {
                bestF05 = f05;
                bestThreshold = thresh;
                bestReport = String.format(
                    "Порог тревоги: %.1f%%\nTP: %d | FP: %d | FN: %d | TN: %d\nPrecision: %.2f%% | Recall: %.2f%% | F0.5: %.2f%%",
                    thresh, tp, fp, fn, tn, precision, recall, f05
                );
            }
        }

        System.out.println("=== 🏆 РЕЗУЛЬТАТ МОДЕЛИ [" + modelName + "] ===");
        if (bestReport.isEmpty()) {
            System.out.println("⚠️ Не удалось найти порог.");
        } else {
            System.out.println(bestReport);
        }
        System.out.println("=====================================================");
    }
}