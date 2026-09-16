package org.gmra.service;

import java.time.LocalDate;
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

    public PredictionResult calculateProbability(LocalDate baseDate, LocalDate forecastDate, int daysWindow) {
        if (mathService.cachedWeights == null) { mathService.cachedWeights = systemDao.getScoringWeights(); }
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        // look for the indexes of the base date (where we look) and the target date (forecast).
        int baseIndex = -1;
        int forecastIndex = -1;
        for (int i = 0; i < allData.size(); i++) {
            LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
            if (rowDate.equals(baseDate)) baseIndex = i;
            if (rowDate.equals(forecastDate)) forecastIndex = i;
        }
        // If there is no data for the target date (in the real future), return null
        if (baseIndex == -1 || baseIndex < daysWindow || forecastIndex == -1) {
            return null;
        }
        // SEISMIC WINDOW: We strictly fix the baseDate without looking into future
        List<Map<String, Object>> windowData = allData.subList(baseIndex - daysWindow, baseIndex);
        
        double[] windowBenioff = mathService.extractDoubleArray(windowData, "benioff_strain");
        double[] windowMagnitudes = mathService.extractDoubleArray(windowData, "sum_magnitude");
        double[] windowDepths = mathService.extractDoubleArray(windowData, "avg_depth");
        double[] windowBValues = mathService.extractDoubleArray(windowData, "b_value");
        double[] windowEventCounts = mathService.extractDoubleArray(windowData, "event_count");
        
        PredictionResult result = new PredictionResult();
        result.targetDate = forecastDate;
        result.windowSize = daysWindow;
        double currentScore = 0.0;
        
        double theoreticalMaxScore = mathService.cachedWeights.getTotalMaxScore();
        if (theoreticalMaxScore == 0) return result;
        
        // ASTRONOMY: Scanning entire window from the base date to the forecast date
        // =========================================================================
        boolean hasAphelion = false, hasAphelionProx = false, hasEquinox = false;
        boolean hasMoonArea = false, hasMomentumExt = false, hasSpeedExt = false;
        for (int j = baseIndex; j <= forecastIndex; j++) {
            LocalDate evalDate = ((java.sql.Date) allData.get(j).get("stat_date")).toLocalDate();
            
            long apside = mathService.getDaysToNearestApside(evalDate);
            if (apside <= 16) hasAphelion = true;
            else if (apside <= 35 && apside > 16) hasAphelionProx = true;
            
            if (mathService.getDaysToNearestEquinox(evalDate) <= 16) hasEquinox = true;
            if (mathService.isMoonAreaPhaseShift(allData, j, daysWindow)) hasMoonArea = true;
            if (mathService.isSpeedDerivativeExtremum(allData, j)) hasMomentumExt = true;
            
            int days = mathService.isNearMoonSpeedExtremumGradient(allData, j);
            if (days <= 3 && days >= 0) hasSpeedExt = true;
        }

        if (hasAphelion) { 
            currentScore += mathService.cachedWeights.weightAphelion; 
            result.activeTriggers.add("Aphelion/Perihelion (≤16d)");
        } else if (hasAphelionProx) { 
            currentScore += mathService.cachedWeights.weightAphelion * 0.8; 
            result.activeTriggers.add("Aphelion/Perihelion (≤35d)");
        }
        
        if (hasEquinox) {
            currentScore += mathService.cachedWeights.weightEquinox;
            result.activeTriggers.add("Equinox Proximity (≤16d)");
        }
        if (hasMoonArea) {
            currentScore += mathService.cachedWeights.weightMoonAreaPhase;
            result.activeTriggers.add("Moon Orbital Phase Shift");
        }
        if (hasMomentumExt) {
            currentScore += mathService.cachedWeights.weightMomentumExt;
            result.activeTriggers.add("Moon Acceleration Extremum");
        }
        if (hasSpeedExt) {
            currentScore += mathService.cachedWeights.weightSpeedMoonExt; 
            result.activeTriggers.add("Moon Speed Extremum");
        }

        // SEISMIC CORRELATIONS
        // ====================
        double pearsonToday = Math.abs(mathService.getPearsonCorrelation(windowMagnitudes, windowDepths));
        double spearmanToday = Math.abs(mathService.getSpearmanCorrelation(windowMagnitudes, windowDepths));
        int yesterdayStart = baseIndex - daysWindow - 1;
        int yesterdayEnd = baseIndex - 1;
        
        // Default gradient at start = 0
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
        if (pearsonToday >= 0.85 && Math.abs(pearsonDelta) >= 0.1) {
            currentScore += mathService.cachedWeights.weightPearsonMD;
            result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.85⬆️");
        } 
        else if (pearsonToday >= 0.85) { 
            currentScore += mathService.cachedWeights.weightPearsonMD * 0.85; 
            result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.85");
        }
        else if (pearsonToday >= 0.75) { 
            currentScore += mathService.cachedWeights.weightPearsonMD * 0.75; 
            result.activeTriggers.add("Pearson: Mag vs Depth ≥ 0.75");
        }
        double spearmanDelta = spearmanToday - spearmanYesterday;
        if (spearmanToday >= 0.85 && Math.abs(spearmanDelta) >= 0.1) { 
            currentScore += mathService.cachedWeights.weightSpearmanMD; 
            result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.85");
        }
        else if (spearmanToday >= 0.85) { 
            currentScore += mathService.cachedWeights.weightSpearmanMD * 0.85; 
            result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.85");
        }
        else if (spearmanToday >= 0.75) { 
            currentScore += mathService.cachedWeights.weightSpearmanMD * 0.75; 
            result.activeTriggers.add("Spearman: Mag vs Depth ≥ 0.75");
        }
        // Benioff's energy acceleration
        //==============================
        double[] velocityBenioff = mathService.calculateDerivative(windowBenioff);
        if (velocityBenioff.length > 0) {
            double currentEnergySpeed = velocityBenioff[velocityBenioff.length - 1];
            
            // The energy decreased
            if (mathService.isEnergyQuiescenceAfterPeak(windowBenioff)) {
                currentScore += mathService.cachedWeights.weightSeismicQuiescence;
                result.activeTriggers.add("Benioff's Sharp Energy Drop");
            } 
            // The energy increase
            else if (currentEnergySpeed > mathService.baselineSigma.get("benioff_strain")) {
                currentScore += mathService.cachedWeights.weightEnergyAcceleration;
                result.activeTriggers.add("Benioff's Active Energy Accel.");
            }
        } 
        // Depth Sigma Anomaly
        // ====================
        if (windowDepths.length >= 2) {
            double depthToday = windowDepths[windowDepths.length - 1];
            double depthYesterday = windowDepths[windowDepths.length - 2];
            double depthDelta = depthToday - depthYesterday; 
            
            if (depthDelta < 0) { // ONLY if the pressure upwards
                double normalDepthSigma = mathService.baselineSigma.get("global_depth");            
                double depthAccelerationRatio = Math.abs(depthDelta) / normalDepthSigma;

                if (depthAccelerationRatio > 0.5) { 
                    currentScore += mathService.cachedWeights.weightDepthAnomaly;
                    result.activeTriggers.add("Depth Anomaly: Violent Shift⬆️");
                }
                else if (depthAccelerationRatio > 0.3) { 
                    currentScore += mathService.cachedWeights.weightDepthAnomaly * 0.8; 
                    result.activeTriggers.add("Depth Anomaly: Sharp Shift⬆️");
                }
                else if (depthAccelerationRatio > 0.2) { 
                    currentScore += mathService.cachedWeights.weightDepthAnomaly * 0.55; 
                    result.activeTriggers.add("Depth Anomaly: Mod. Shift⬆️");
                }
            }
        }
        // b-value Anomaly
        if (windowBValues.length > 0) {
            double currentBValue = windowBValues[windowBValues.length - 1]; 
            double[] velocityB = mathService.calculateDerivative(windowBValues);
            double currentBSpeed = velocityB.length > 0 ? velocityB[velocityB.length - 1] : 0.0;
            
            // Change vector: Beginning of the Up
            if (currentBValue < 1.0 && currentBValue >= 0.5 && currentBSpeed > 0) {
                currentScore += mathService.cachedWeights.weightBValueAnomaly; 
                result.activeTriggers.add("b-value Anomaly (0.5 - 1.0) ⬆️⇅");
            }
            // Change vector: Beginning of the Down
            else if (currentBValue > 1.0 && currentBValue <= 1.3 && currentBSpeed < 0) {
                currentScore += mathService.cachedWeights.weightBValueAnomaly * 0.85; 
                result.activeTriggers.add("b-value Anomaly (1.0 - 1.3) ⬇️⇅");
            }
            // Default
            else if (currentBValue < 1.0 && currentBValue >= 0.9) {
                currentScore += mathService.cachedWeights.weightBValueAnomaly; 
                result.activeTriggers.add("b-value Anomaly (0.9 - 1.0)");
            }
            else if (currentBValue < 0.9 && currentBValue >= 0.5) {
                currentScore += mathService.cachedWeights.weightBValueAnomaly * 0.85; 
                result.activeTriggers.add("b-value Anomaly (0.5 - 0.9)");
            }
        }
        // ETAS Seismic Swarm
        //=================================
        /**		---- Two-level ----		**/ 
        /** ---- linear regression ---- **/
        int macroStartIndex = Math.max(0, baseIndex - 6);  // Subwindow size
        List<Map<String, Object>> macroWindowData = allData.subList(macroStartIndex, baseIndex);
        double[] macroEventCounts = mathService.extractDoubleArray(macroWindowData, "event_count");
        if (windowEventCounts.length >= 2 && macroEventCounts.length >= 3) {
            double currentCount = windowEventCounts[windowEventCounts.length - 1];
            double currentEventSpeed = currentCount - windowEventCounts[windowEventCounts.length - 2];
            double overallTrend = mathService.calculateTrendSlope(macroEventCounts);
            double normalEventCountSigma = mathService.baselineSigma.get("event_count");
            
            if (currentEventSpeed > 0) {
                double swarmAccelerationRatio = currentEventSpeed / normalEventCountSigma;
                boolean isSustainedSwarm = overallTrend > 0; 
                
                if (swarmAccelerationRatio > 0.25) { 
                    currentScore += mathService.cachedWeights.weightEventSwarm; 
                    result.activeTriggers.add(isSustainedSwarm ? "ETAS Swarm: Extreme Accel.⬆️" : "ETAS Swarm: Extreme Accel.");
                }
                else if (swarmAccelerationRatio > 0.15) { 
                    currentScore += mathService.cachedWeights.weightEventSwarm * 0.83; 
                    result.activeTriggers.add(isSustainedSwarm ? "ETAS Swarm: High Accel.⬆️" : "ETAS Swarm: High Accel.");
                }
                else if (swarmAccelerationRatio > 0.08) { 
                    currentScore += mathService.cachedWeights.weightEventSwarm * 0.53; 
                    result.activeTriggers.add(isSustainedSwarm ? "ETAS Swarm: Moderate Accel.⬆️" : "ETAS Swarm: Moderate Accel.");
                }
            }
            // Seismic silence
            else if (currentEventSpeed <= 0) {
                double dropRatio = Math.abs(currentEventSpeed) / normalEventCountSigma;
                if (overallTrend > 0 || dropRatio > 0.07) {
                    // A sharp break in Up-trend or abnormally strong decline
                    currentScore += mathService.cachedWeights.weightEventSwarm;
                    result.activeTriggers.add("ETAS Swarm: U-turn / Lock-in");
                }
            }
        }
     // === DEFINITION OF "SYNERGY" ===
        boolean hasGravitationalCore = false;
        int seismicCoreCount = 0;
        
        // Super Combinations (Red Zone)
        boolean hasCriticalPearson = false;
        boolean hasCriticalBenioff = false;
        boolean hasCriticalDepth = false;
        boolean hasCriticalBValue = false;
        
        for (String t : result.activeTriggers) {
            if (t.contains("Moon") || t.contains("Aphelion") || t.contains("Equinox")) {
                hasGravitationalCore = true;
            }
            if (t.contains("Benioff") || t.contains("Quiescence") || t.contains("ETAS") || 
                t.contains("b-value") || t.contains("Depth") || t.contains("Pearson")) {
                seismicCoreCount++;
            }
            // RED ZON
            if (t.contains("Pearson: Mag vs Depth ≥ 0.85⬆️")) hasCriticalPearson = true;
            if (t.contains("Benioff's Active Energy Accel.")) hasCriticalBenioff = true;
            if (t.contains("Depth Anomaly: Violent Shift⬆️")) hasCriticalDepth = true;
            if (t.contains("b-value Anomaly (0.5 - 1.0) ⬆️⇅") || t.contains("b-value Anomaly (0.9 - 1.0)")) {
                hasCriticalBValue = true;
            }
        }

        // Basic Probability
        double rawProbability = (currentScore / theoreticalMaxScore) * 100.0;
        currentScore = Math.min(rawProbability, 99.9);
        currentScore = Math.round(currentScore * 100.0) / 100.0;
       
        // RED ZONE OVERRIDE (hasCriticalBenioff == off)
        if (hasCriticalPearson && hasCriticalDepth && hasCriticalBValue && currentScore <= 75.0) {
        	result.activeTriggers.add("🚨 SYNERGY: Multi-Seismic Alignment ("+currentScore+"% ⇄ 85%)"); 
        	currentScore = 85.0; 
        }
        // SYNERGY OVERRIDE
        else if (hasGravitationalCore && seismicCoreCount >= 2) {
            if (currentScore < 50) { currentScore += 25.0; result.activeTriggers.add("⚡ SYNERGY: Astro + Multi-Seismic Alignment (+25%)"); }
            else if (currentScore >= 50 && currentScore < 60) { currentScore += 15.0; result.activeTriggers.add("⚡ SYNERGY: Astro + Multi-Seismic Alignment (+15%)"); }
            else { currentScore += 3.0; result.activeTriggers.add("⚡ SYNERGY: Astro + Multi-Seismic Alignment (+3%)"); }
        } 
        else if (hasGravitationalCore && seismicCoreCount == 1) {
            currentScore += 7.0; result.activeTriggers.add("⚡ SYNERGY: Astro + Single Seismic Anomaly (+7%)");
        }
        else if (currentScore == 0.0) { // suspicious silence
            currentScore = 49.99; result.activeTriggers.add("⚡ Suspicious silence (+49.99%)");
        }

        result.probabilityPercent = Math.min(currentScore, 99.9);
        result.probabilityPercent = Math.round(result.probabilityPercent * 100.0) / 100.0;
        return result;
    }
	
}