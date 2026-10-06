package org.gmra.service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.gmra.dao.SystemDao;
import org.gmra.dto.PredictionResult;
import org.springframework.stereotype.Service;

@Service
public class PredictionCalculator {
    
    private final SystemDao systemDao;    
    private final MathCalculationService mathService;
    
    public PredictionCalculator(SystemDao systemDao,
    		MathCalculationService mathCalculationService) {
    	
        this.systemDao = systemDao;
        this.mathService = mathCalculationService;
    }
    
    public PredictionResult calculateProbability(List<Map<String, Object>> allData, LocalDate baseDate, LocalDate forecastDate, int daysWindow) {
        if (mathService.cachedWeights == null) { mathService.cachedWeights = systemDao.getScoringWeights(); }
       
        int baseIndex = -1;
        int forecastIndex = -1;
        for (int i = 0; i < allData.size(); i++) {
            LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
            if (rowDate.equals(baseDate)) baseIndex = i;
            if (rowDate.equals(forecastDate)) forecastIndex = i;
        }
   
        if (baseIndex == -1 || baseIndex < daysWindow || forecastIndex == -1) {
            return null;
        }
        
        boolean hasPearson = false;
        boolean hasEtas = false; 
        boolean hasBValue = false;
        boolean hasDepth = false;
        boolean hasBenioff = false;
        boolean hasApside = false;
        boolean hasMoon = false;
        
        // СЕЙСМИЧЕСКОЕ ОКНО: Строго фиксируем по baseDate (без заглядывания в будущее!)
        // Окно берет дни строго ДО базовой даты, копируя логику из setScoringConfig
        int daysWindowForEtas = 7;
        List<Map<String, Object>> windowDataEtas = allData.subList(Math.max(0, baseIndex - daysWindowForEtas), baseIndex);
        List<Map<String, Object>> windowData = allData.subList(baseIndex - daysWindow, baseIndex);
        double[] windowBenioff = mathService.extractDoubleArray(windowData, "benioff_strain");
        double[] windowDepths = mathService.extractDoubleArray(windowData, "avg_depth");
        double[] windowBValues = mathService.extractDoubleArray(windowData, "b_value");
        double[] windowMagnitudes = mathService.extractDoubleArray(windowData, "sum_magnitude");
        double[] windowMagnitudesForEtas = mathService.extractDoubleArray(windowDataEtas, "max_magnitude");
        
        PredictionResult result = new PredictionResult();
        result.targetDate = forecastDate;
        result.windowSize = daysWindow;
        double currentScore = 0.0;
        
        double theoreticalMaxScore = mathService.cachedWeights.getTotalMaxScore();
        if (theoreticalMaxScore == 0) return result;
        
        // ASTRONOMY
        //====================================================
        // 1. Earth near Aphelion / Perihelion
        long apside = mathService.getDaysToNearestApside(forecastDate);
        if (apside <= 35 && apside >= 14) {
        	currentScore += mathService.cachedWeights.weightAphelion * 0.8;
        	result.activeTriggers.add("Aphelion/Perihelion (≤35d)"); 
        	hasApside = true;
        }
        else if (apside <= 16) { // Earth near Aphelion/Perihelion
        	currentScore += mathService.cachedWeights.weightAphelion; 
        	result.activeTriggers.add("Aphelion/Perihelion (≤16d)");
        	hasApside = true;
        }
        // 2. Earth near Equinox (<= 16 days / half-moon rotation Max.)
        if (mathService.getDaysToNearestEquinox(forecastDate) <= 16) {
            currentScore += mathService.cachedWeights.weightEquinox;
            result.activeTriggers.add("Equinox Proximity (≤16d)");
            hasMoon = true;
        }
        // 3. Phase change of the Moon's orbital area
        if (mathService.isMoonAreaPhaseShift(allData, forecastIndex, daysWindow)) {
            currentScore += mathService.cachedWeights.weightMoonAreaPhase;
            result.activeTriggers.add("Moon Orbital Area Phase Shift");
            hasMoon = true;
        }
        // 4. Momentum Derivatives are right at their peak
        if (mathService.isSpeedDerivativeExtremum(allData, forecastIndex)) {
            currentScore += mathService.cachedWeights.weightMomentumExt;
            result.activeTriggers.add("Moon Acceleration Extremum");
            hasMoon = true;
        }
        // 5. Moon speed <= 2 days from extreme
        int days = mathService.isNearMoonSpeedExtremumGradient(allData, forecastIndex);
        if (days<=2 && days>=0) {
        	currentScore += mathService.cachedWeights.weightSpeedMoonExt; 
        	result.activeTriggers.add("Moon Speed Extremum ("+days+"d)");
        	hasMoon = true;
        }
        // ========================
        //  -- SEISMIC TRIGGERS ---
        // ========================
        //  6. Pearson & 7. Spearman
        double pearsonToday = Math.abs(mathService.getPearsonCorrelation(windowMagnitudes, windowDepths));
        double spearmanToday = Math.abs(mathService.getSpearmanCorrelation(windowMagnitudes, windowDepths));
        int yesterdayStart = baseIndex - daysWindow - 1;
        int yesterdayEnd = baseIndex - 1;
        
        double pearsonYesterday = pearsonToday;
        double spearmanYesterday = spearmanToday;
        if (yesterdayStart >= 0 && daysWindow >= 3) {
            List<Map<String, Object>> windowDataYesterday = allData.subList(yesterdayStart, yesterdayEnd);
            double[] windowMagYesterday = mathService.extractDoubleArray(windowDataYesterday, "sum_magnitude");
            double[] windowDepthYesterday = mathService.extractDoubleArray(windowDataYesterday, "avg_depth");
            
            if (windowMagYesterday.length >= 3) {
                pearsonYesterday = Math.abs(mathService.getPearsonCorrelation(windowMagYesterday, windowDepthYesterday));
                spearmanYesterday = Math.abs(mathService.getSpearmanCorrelation(windowMagYesterday, windowDepthYesterday));
            }
        }
        
        double pearsonDelta = pearsonToday - pearsonYesterday;
        if (pearsonToday >= 0.85 && Math.abs(pearsonDelta) >= 0.1) { currentScore += mathService.cachedWeights.weightPearsonMD; result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.85⬆️"); hasPearson = true;} 
        else if (pearsonToday >= 0.85) { currentScore += mathService.cachedWeights.weightPearsonMD * 0.85; result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.85"); hasPearson = true;}
        else if (pearsonToday >= 0.75) { currentScore += mathService.cachedWeights.weightPearsonMD * 0.75; result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.75"); hasPearson = true;}
        
        double spearmanDelta = spearmanToday - spearmanYesterday;
        if (spearmanToday >= 0.85 && Math.abs(spearmanDelta) >= 0.1) { currentScore += mathService.cachedWeights.weightSpearmanMD; result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.85"); }
        else if (spearmanToday >= 0.85) { currentScore += mathService.cachedWeights.weightSpearmanMD * 0.85; result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.85"); }
        else if (spearmanToday >= 0.75) { currentScore += mathService.cachedWeights.weightSpearmanMD * 0.75; result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.75"); }
        
        // 7. Benioff's energy acceleration
        double[] velocityBenioff = mathService.calculateDerivative(windowBenioff);
        if (velocityBenioff.length > 0) {
            double currentEnergySpeed = velocityBenioff[velocityBenioff.length - 1];
            if (mathService.isEnergyQuiescenceAfterPeak(windowBenioff)) {
                currentScore += mathService.cachedWeights.weightSeismicQuiescence;
                result.activeTriggers.add("Benioff's Sharp Energy Drop");
                hasBenioff = true;
            } 
            else if (currentEnergySpeed > mathService.baselineSigma.get("benioff_strain")) {
                currentScore += mathService.cachedWeights.weightEnergyAcceleration;
                result.activeTriggers.add("Benioff's Active Energy Accel.");
                hasBenioff = true;
            }
        } 
        // 8. Depth Sigma Anomaly
        if (windowDepths.length >= 2) {
            double depthToday = windowDepths[windowDepths.length - 1];
            double depthYesterday = windowDepths[windowDepths.length - 2];
            double depthDelta = depthToday - depthYesterday; 
            
            if (depthDelta < 0) { 
                double normalDepthSigma = mathService.baselineSigma.get("global_depth");            
                double depthAccelerationRatio = Math.abs(depthDelta) / normalDepthSigma;

                if (depthAccelerationRatio > 0.5) { currentScore += mathService.cachedWeights.weightDepthAnomaly; result.activeTriggers.add("Depth Anomaly: Violent Shift⬆️"); hasDepth = true;}
                else if (depthAccelerationRatio > 0.3) { currentScore += mathService.cachedWeights.weightDepthAnomaly * 0.8; result.activeTriggers.add("Depth Anomaly: Sharp Shift⬆️"); hasDepth = true;}
                else if (depthAccelerationRatio > 0.2) { currentScore += mathService.cachedWeights.weightDepthAnomaly * 0.55; result.activeTriggers.add("Depth Anomaly: Mod. Shift⬆️"); hasDepth = true;}
            }
        }
        // 9. b-value Anomaly & Info Triggers
        if (windowBValues.length > 0) {
            double currentBValue = windowBValues[windowBValues.length - 1]; 
            double[] velocityB = mathService.calculateDerivative(windowBValues);
            double currentBSpeed = velocityB.length > 0 ? velocityB[velocityB.length - 1] : 0.0;
            
            if (currentBValue < 1.0 && currentBValue >= 0.5 && currentBSpeed > 0) { currentScore += mathService.cachedWeights.weightBValueAnomaly; result.activeTriggers.add("b-value (0.5 - 1.0) ⬆️⇅"); hasBValue = true;}
            else if (currentBValue > 1.0 && currentBValue <= 1.3 && currentBSpeed < 0) { currentScore += mathService.cachedWeights.weightBValueAnomaly * 0.85; result.activeTriggers.add("b-value (1.0 - 1.3) ⬇️⇅"); hasBValue = true;}
            else if (currentBValue < 1.0 && currentBValue >= 0.9) { currentScore += mathService.cachedWeights.weightBValueAnomaly; result.activeTriggers.add("b-value (0.9 - 1.0)"); hasBValue = true;}
            else if (currentBValue < 0.9 && currentBValue >= 0.5) { currentScore += mathService.cachedWeights.weightBValueAnomaly * 0.85; result.activeTriggers.add("b-value (0.5 - 0.9)"); hasBValue = true;}

            // INFORMATIONAL b-value
            if (!hasBValue)
            for (int vInt = 5; vInt < 15; vInt++) {
                double v = vInt / 10.0;
                double vNext = (vInt + 1) / 10.0;
                if (currentBValue >= v && currentBValue < vNext) {
                    String infoTrigger = String.format(java.util.Locale.US,"b-value (%.1f - %.1f)", v, vNext);
                    if (!result.activeTriggers.contains(infoTrigger)) {
                        result.activeTriggers.add(infoTrigger);
                    }
                    break;
                }
            }
        }  
        // ============================================
        // 10. ETAS Swarm (Ogata Model / Omori-Utsu Law)
        // ============================================
        if (windowMagnitudesForEtas.length >= 7) {
                // Global constants from the database
                double mu = 819.35; // mean
                double mc = 4.5;    // Magnitude threshold

                double lambdaToday = mathService.calculateEtasIntensity(windowMagnitudesForEtas, mu, mc);
                double[] magnitudesYesterday = Arrays.copyOfRange(windowMagnitudesForEtas, 0, windowMagnitudesForEtas.length - 1);
                double lambdaYesterday = mathService.calculateEtasIntensity(magnitudesYesterday, mu, mc);

                // Anomaly coefficient (lambda / 819.35)
                double swarmRatio = lambdaToday / mu;
                double lambdaDelta = lambdaToday - lambdaYesterday;
         
                // If the planetary background > 10%
                if (swarmRatio > 1.10) { 
                    hasEtas = true;
                    if (lambdaDelta > 0) {
                        if (swarmRatio > 1.60) { // Burst > 1300 events (Equivalent to a M7.0+ burst)
                            currentScore += mathService.cachedWeights.weightEventSwarm;
                            result.activeTriggers.add("ETAS Swarm: Extreme Acceleration (Ogata/Omori-Utsu)");
                        } else if (swarmRatio > 1.30) { // Burst > 1050 events
                            currentScore += mathService.cachedWeights.weightEventSwarm * 0.83;
                            result.activeTriggers.add("ETAS Swarm: High Acceleration (Ogata/Omori-Utsu)");
                        } else { // Burst > 900 events
                            currentScore += mathService.cachedWeights.weightEventSwarm * 0.53;
                            result.activeTriggers.add("ETAS Swarm: Moderate Accel. (Ogata/Omori-Utsu)");
                        }
                    } else {
                        // The swarm is fading away amid still high tension
                        currentScore += mathService.cachedWeights.weightEventSwarm;
                        result.activeTriggers.add("ETAS Swarm: U-turn / Lock-in (Ogata/Omori-Utsu) ⇅");
                    }
                }
            }

        // --- GENERAL FILTER ---
        //=======================
        double probability = (theoreticalMaxScore > 0) ? (currentScore / theoreticalMaxScore) * 100.0 : 0.0;

        // ================
        // SYNERGY OVERRIDE
        // ================
        if ((hasApside || hasMoon) && hasBValue && hasEtas && hasDepth || 
        	(hasApside || hasMoon) && hasBValue && (hasEtas || hasDepth)) {        	
        	if (probability < 75) {probability += 15.0; result.activeTriggers.add("⚡ Astro + Multi-Seismic (+15%)");}
        	else {result.activeTriggers.add("⚡ Astro + Multi-Seismic");}
        }
        else if (hasBValue && hasEtas && hasDepth) {
        	if (probability < 75) {probability += 5.0; result.activeTriggers.add("⚡ SYNERGY: Multi-Seismic (+10%)");}
        	else {result.activeTriggers.add("⚡ Multi-Seismic Alignment");}
        }
        else if (probability < 20.0) { // suspicious silence
        	probability = 30.01; result.activeTriggers.add("⚡ Suspicious silence (30%)");
        }
        	
        result.probabilityPercent = Math.min(probability, 99.9);
        result.probabilityPercent = Math.round(result.probabilityPercent * 100.0) / 100.0;
        return result;
    }
    
    
   //**************************---- DATA ML ----***************************************
   //==================================================================================
    
    public double[] extractFeatures(List<Map<String, Object>> allData, int baseIndex) {
    	// baseIndex - это текущий день (выбранная дата) и мы его не включаем в выборку.
        int daysWindow7d = 7;
        int daysWindow3d = 3;
        List<Map<String, Object>> windowData7d = allData.subList(Math.max(0, baseIndex - daysWindow7d + 1), baseIndex + 1);
        List<Map<String, Object>> windowData3d = allData.subList(Math.max(0, baseIndex - daysWindow3d + 1), baseIndex + 1);
        
        // ==========================================
        // 1. АСТРОНОМИЯ (Moon Speed Extremum)
        // ==========================================
        double moonSpeedExtremum = mathService.isNearMoonSpeedExtremumGradient(allData, baseIndex);
        
        // ==========================================
        // 1. НАПРЯЖЕНИЕ (b-value)
        // ==========================================
        double[] windowBValues = mathService.extractDoubleArray(windowData3d, "b_value");
        double currentBValue = windowBValues.length > 0 ? windowBValues[windowBValues.length - 1] : 0.0;
        
        // ==========================================
        // 2. СЕЙСМИЧЕСКИЙ РОЙ (ETAS)
        // ==========================================
        double[] windowMagnitudesForEtas = mathService.extractDoubleArray(windowData7d, "max_magnitude");
        double mu = 819.35; 
        double mc = 4.5;    
        double lambdaToday = mathService.calculateEtasIntensity(windowMagnitudesForEtas, mu, mc);
        double etasSwarmRatio = lambdaToday / mu;
        
        // ==========================================
        // 4. КИНЕМАТИКА (Pearson 7d vs 3d)
        // ==========================================
        double[] windowMagnitudes3d = mathService.extractDoubleArray(windowData3d, "sum_magnitude");
        double[] windowDepths3d = mathService.extractDoubleArray(windowData3d, "avg_depth");
        double pearson3d = mathService.getPearsonCorrelation(windowMagnitudes3d, windowDepths3d);

        int trendDays = 3;
        double[] pearsonHistory = new double[trendDays];
        for (int i = 0; i < trendDays; i++) {
            int endIdx = baseIndex - trendDays + i;
            int startIdx = Math.max(0, endIdx - daysWindow3d);
            
            // Если индекс уходит в минус на самом старте истории базы, страхуемся
            if (startIdx < 0) startIdx = 0;
            
            List<Map<String, Object>> subWindow = allData.subList(startIdx, endIdx);
            double[] subMags = mathService.extractDoubleArray(subWindow, "sum_magnitude");
            double[] subDeps = mathService.extractDoubleArray(subWindow, "avg_depth");
            
            double p = mathService.getPearsonCorrelation(subMags, subDeps);
            // Если дисперсия нулевая, Pearson может вернуть NaN. Заменяем на 0.
            pearsonHistory[i] = Double.isNaN(p) ? 0.0 : p; 
        }
        // Вычисляем истинный наклон тренда через линейную регрессию
        double pearsonTrend = mathService.calculateTrendSlope(pearsonHistory);

        // ==========================================
        // 5. ГЛУБИНА (Текущая + Дельта + Ускорение)
        // ==========================================
        double depthToday = 0.0;
        double depthDelta = 0.0;
        double depthAccel = 0.0;

        if (windowDepths3d.length >= 3) {
            int len = windowDepths3d.length;
            depthToday = windowDepths3d[len - 1];
            double depthYesterday = windowDepths3d[len - 2];
            double depthDayBefore = windowDepths3d[len - 3];
            
            depthDelta = depthToday - depthYesterday;
            
            double velocityToday = depthToday - depthYesterday;
            double velocityYesterday = depthYesterday - depthDayBefore;
            depthAccel = velocityToday - velocityYesterday;
        }
        // ==========================================
        // 6. ЭНЕРГИЯ (Benioff + Ускорение)
        // ==========================================
        double[] windowBenioff = mathService.extractDoubleArray(windowData3d, "benioff_strain");
        double currentBenioff = windowBenioff[windowBenioff.length - 1];
        windowBenioff = mathService.extractDoubleArray(windowData7d, "benioff_strain");
        double velocityToday = 0.0;
        double velocityYesterday = 0.0;
        if (windowBenioff.length >= 3) {
            velocityToday = windowBenioff[windowBenioff.length - 1] - windowBenioff[windowBenioff.length - 2];
            velocityYesterday = windowBenioff[windowBenioff.length - 2] - windowBenioff[windowBenioff.length - 3];
        }
        double benioffAccel = velocityToday - velocityYesterday;
        
        // USGS water temperature;
    	Object meanObj = allData.get(baseIndex).get("median_temp");
    	double currentMeanTemp = (meanObj != null) ? ((Number) meanObj).doubleValue() : 0.0;
    	double[] windowMeanTemp = mathService.extractDoubleArray(windowData7d, "median_temp");
    	double deltaTemp = windowMeanTemp[windowMeanTemp.length-1] - windowMeanTemp[windowMeanTemp.length-2];
        double[] velocityTemp = mathService.calculateDerivative(windowMeanTemp);
        double waterTrend = mathService.calculateTrendSlope(windowMeanTemp);
      
        velocityToday = velocityTemp[velocityTemp.length - 1] - velocityTemp[velocityTemp.length - 2];
        velocityYesterday = velocityTemp[velocityTemp.length - 2] - velocityTemp[velocityTemp.length - 3];
        double tempAccel = velocityToday - velocityYesterday;
        
    	
        // ==========================================
        // ИТОГОВЫЙ ВЕКТОР
        // ==========================================
        return new double[] {
            currentBenioff,       // [0] Текущая энергия
            benioffAccel,         // [1] Ускорение выброса энергии
            etasSwarmRatio,       // [2] Рой (самодостаточный)
            depthToday,           // [3] Текущая глубина
            depthDelta, 		  // [4] Дельта глубины (модуль рывка)
            depthAccel,           // [5] Ускорение сдвига глубины
            currentBValue,        // [6] Напряжение (самодостаточный)
            pearson3d,            // [7] Базовый Пирсон (7 дней)
            pearsonTrend,         // [8] Динамика Пирсона (3д относительно 7д)
            moonSpeedExtremum,    // [9] Гравитационный триггер
            deltaTemp,			  // [10] Динамика температуры Firehole River 
            tempAccel,			  // [11] Скорость изминения температуры Firehole River 
            waterTrend			  // [12] Вектор движения температуры Firehole River
        };
    }
}